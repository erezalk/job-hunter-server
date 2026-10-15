package dev.erezalkalay.jobhunter.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Request validation of settings (Board columns, theme, timezone) and of the
 * free-text job description / notes limits. Rejected requests are 4xx and
 * leave the stored values exactly as they were. Uses its own user.
 */
class InputValidationIntegrationTest extends IntegrationTestBase {

    private static final String EMAIL = "validation-user@jobhunter.local";
    private static final String PASSWORD = "validation-password-123";
    private static final int DESCRIPTION_LIMIT = 100_000;
    private static final int NOTES_LIMIT = 50_000;

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        testData.createUserIfMissing(EMAIL, PASSWORD);
        client = new TestApiClient(port);
        assertThat(client.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(putSettings("SYSTEM", "UTC", "[\"NEW\",\"CV_SENT\"]").statusCode()).isEqualTo(200);
    }

    // --- Board columns ---

    @ParameterizedTest(name = "visibleBoardStatuses = {0}")
    @ValueSource(strings = {"[\"NEW\",\"FOO\"]", "[\"NEW,CV_SENT\"]", "[\"new\"]", "[\"\"]", "[\" NEW\"]", "[null]", "[]"})
    void invalidBoardColumnSelectionsAreRejectedAndChangeNothing(String statuses) throws Exception {
        String before = client.get("/api/settings").body();

        HttpResponse<String> response = putSettings("SYSTEM", "UTC", statuses);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(client.get("/api/settings").body()).isEqualTo(before);
    }

    @Test
    void anUnknownBoardColumnIsNamedInTheError() throws Exception {
        HttpResponse<String> response = putSettings("SYSTEM", "UTC", "[\"NEW\",\"FOO\"]");

        assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo("Unknown Board column status: FOO");
    }

    @Test
    void everyRealStatusIncludingLowMatchIsAccepted() throws Exception {
        String all = "[\"NEW\",\"CV_SENT\",\"INTERVIEW_PROCESS\",\"OFFER\",\"REJECTED\",\"ARCHIVED\",\"LOW_MATCH\"]";

        HttpResponse<String> response = putSettings("SYSTEM", "UTC", all);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(client.get("/api/settings").body(), "$.visibleBoardStatuses"))
                .containsExactly("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER", "REJECTED", "ARCHIVED", "LOW_MATCH");
    }

    // --- Theme ---

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "LIGHT", "DARK"})
    void everySupportedThemeIsAccepted(String theme) throws Exception {
        assertThat(putSettings(theme, "UTC", "[\"NEW\"]").statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(client.get("/api/settings").body(), "$.theme")).isEqualTo(theme);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PURPLE", "dark", "Light", "SYSTEM "})
    void anyOtherThemeIsAFieldErrorAndChangesNothing(String theme) throws Exception {
        String before = client.get("/api/settings").body();

        HttpResponse<String> response = putSettings(theme, "UTC", "[\"NEW\"]");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("theme");
        assertThat(client.get("/api/settings").body()).isEqualTo(before);
    }

    // --- Timezone ---

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Berlin", "Asia/Jerusalem", "America/New_York"})
    void realTimezonesAreAcceptedAsTyped(String timezone) throws Exception {
        assertThat(putSettings("SYSTEM", timezone, "[\"NEW\"]").statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(client.get("/api/settings").body(), "$.timezone")).isEqualTo(timezone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Olympus_Mons", "Europe/Atlantis", "not a timezone"})
    void unknownTimezonesAreRejectedAndChangeNothing(String timezone) throws Exception {
        String before = client.get("/api/settings").body();

        HttpResponse<String> response = putSettings("SYSTEM", timezone, "[\"NEW\"]");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).startsWith("Unknown timezone");
        assertThat(client.get("/api/settings").body()).isEqualTo(before);
    }

    // --- Job description ---

    @Test
    void aLongButRealisticDescriptionIsAccepted() throws Exception {
        HttpResponse<String> response = addJob("Long Description Role", "a".repeat(20_000));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void anOverlargeDescriptionIsRejectedOnAddAndCreatesNothing() throws Exception {
        String title = "Huge Description Role " + System.nanoTime();

        HttpResponse<String> response = addJob(title, "a".repeat(DESCRIPTION_LIMIT + 1));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("description");
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title = ?", title)).isZero();
    }

    @Test
    void anOverlargeDescriptionIsRejectedOnEditAndKeepsTheStoredOne() throws Exception {
        String title = "Edit Description Role " + System.nanoTime();
        long jobId = ((Number) JsonPath.read(addJob(title, "original description").body(), "$.jobId")).longValue();

        HttpResponse<String> response = client.patchJson("/api/jobs/" + jobId,
                "{\"title\":\"" + title + "\",\"description\":\"" + "a".repeat(DESCRIPTION_LIMIT + 1) + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + jobId).body(), "$.description")).isEqualTo("original description");
    }

    @Test
    void theMcpEndpointAppliesTheSameDescriptionLimit() throws Exception {
        String token = JsonPath.read(client.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        String title = "Mcp Huge Description " + System.nanoTime();

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/integrations/jobs"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"" + title + "\",\"description\":\""
                        + "a".repeat(DESCRIPTION_LIMIT + 1) + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("description");
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title = ?", title)).isZero();
    }

    // --- Notes ---

    @Test
    void normalNotesAreSaved() throws Exception {
        long applicationId = trackedApplication();
        String notes = "Recruiter call notes. ".repeat(250);

        HttpResponse<String> response = client.patchJson("/api/applications/" + applicationId + "/notes",
                "{\"notes\":\"" + notes + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.notes")).isEqualTo(notes);
    }

    @Test
    void overlargeGeneralNotesAreRejectedAndKeepTheStoredOnes() throws Exception {
        long applicationId = trackedApplication();
        assertThat(client.patchJson("/api/applications/" + applicationId + "/notes", "{\"notes\":\"keep me\"}")
                .statusCode()).isEqualTo(200);

        HttpResponse<String> response = client.patchJson("/api/applications/" + applicationId + "/notes",
                "{\"notes\":\"" + "n".repeat(NOTES_LIMIT + 1) + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("notes");
        assertThat(JsonPath.<String>read(client.get("/api/applications/" + applicationId).body(), "$.notes"))
                .isEqualTo("keep me");
    }

    @Test
    void overlargeStageNotesAreRejectedAndCreateNoStage() throws Exception {
        long applicationId = trackedApplication();

        HttpResponse<String> response = client.postJson("/api/applications/" + applicationId + "/stages",
                "{\"name\":\"Interview\",\"notes\":\"" + "n".repeat(NOTES_LIMIT + 1) + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<Object>>read(client.get("/api/applications/" + applicationId + "/stages").body(), "$"))
                .isEmpty();
    }

    private HttpResponse<String> putSettings(String theme, String timezone, String statuses) throws Exception {
        return client.putJson("/api/settings", "{\"theme\":\"" + theme + "\",\"timezone\":\"" + timezone
                + "\",\"visibleBoardStatuses\":" + statuses + ",\"minimumFitScore\":70}");
    }

    private HttpResponse<String> addJob(String title, String description) throws Exception {
        return client.postJson("/api/board/jobs",
                "{\"title\":\"" + title + "\",\"companyName\":\"Validation Co\",\"description\":\"" + description + "\"}");
    }

    private long trackedApplication() throws Exception {
        HttpResponse<String> added = addJob("Notes Role " + System.nanoTime(), "Some description");
        return ((Number) JsonPath.read(added.body(), "$.id")).longValue();
    }
}
