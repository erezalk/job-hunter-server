package dev.erezalkalay.jobhunter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A second logged-in user who knows (or guesses) the ids of the first user's
 * Application, stage or Job gets 404 on every browser endpoint, and nothing of
 * the owner's changes.
 */
class ApplicationOwnershipIntegrationTest extends IntegrationTestBase {

    private static final String OTHER_EMAIL = "ownership-other@jobhunter.local";
    private static final String OTHER_PASSWORD = "other-password-123";
    private static final String DESCRIPTION = "Build backend services with Java and Spring Boot.";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    private TestApiClient owner;
    private TestApiClient other;
    private long applicationId;
    private long jobId;
    private long stageId;
    private String title;

    @BeforeEach
    void setUp() throws Exception {
        owner = new TestApiClient(port);
        assertThat(owner.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        testData.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);
        fakeAiProviderState.reset();

        title = "Private Role " + System.nanoTime();
        HttpResponse<String> added = owner.postJson("/api/board/jobs",
                "{\"title\":\"" + title + "\",\"companyName\":\"Private Co\",\"description\":\"" + DESCRIPTION + "\"}");
        assertThat(added.statusCode()).isEqualTo(201);
        applicationId = ((Number) JsonPath.read(added.body(), "$.id")).longValue();
        jobId = ((Number) JsonPath.read(added.body(), "$.jobId")).longValue();
        assertThat(owner.patchJson("/api/applications/" + applicationId + "/notes", "{\"notes\":\"owner notes\"}")
                .statusCode()).isEqualTo(200);
        HttpResponse<String> stage = owner.postJson("/api/applications/" + applicationId + "/stages",
                "{\"name\":\"Owner Interview\",\"notes\":\"owner stage notes\"}");
        assertThat(stage.statusCode()).isEqualTo(201);
        stageId = ((Number) JsonPath.read(stage.body(), "$.id")).longValue();

        // Sanity: the ids are valid for their owner, so a 404 below is about ownership only.
        assertThat(owner.get("/api/applications/" + applicationId).statusCode()).isEqualTo(200);
        assertThat(owner.get("/api/jobs/" + jobId).statusCode()).isEqualTo(200);
    }

    @Test
    void anotherUserCannotReadTheApplication() throws Exception {
        HttpResponse<String> response = other.get("/api/applications/" + applicationId);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(title).doesNotContain("owner notes");
    }

    @Test
    void anotherUserCannotChangeTheStatus() throws Exception {
        int historyBefore = historyRows();

        HttpResponse<String> response = other.patchJson("/api/applications/" + applicationId + "/status",
                "{\"statusCode\":\"OFFER\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownerApplication("$.status.code")).isEqualTo("NEW");
        assertThat(historyRows()).isEqualTo(historyBefore);
    }

    @Test
    void anotherUserCannotUpdateTheNotes() throws Exception {
        HttpResponse<String> response = other.patchJson("/api/applications/" + applicationId + "/notes",
                "{\"notes\":\"overwritten\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownerApplication("$.notes")).isEqualTo("owner notes");
    }

    @Test
    void anotherUserCannotReadTheStatusHistory() throws Exception {
        HttpResponse<String> response = other.get("/api/applications/" + applicationId + "/status-history");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("Application created");
    }

    @Test
    void anotherUserCannotListTheStages() throws Exception {
        HttpResponse<String> response = other.get("/api/applications/" + applicationId + "/stages");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("Owner Interview");
    }

    @Test
    void anotherUserCannotAddAStage() throws Exception {
        HttpResponse<String> response = other.postJson("/api/applications/" + applicationId + "/stages",
                "{\"name\":\"Injected Stage\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownerStageNames()).containsExactly("Owner Interview");
    }

    @Test
    void anotherUserCannotUpdateAStage() throws Exception {
        HttpResponse<String> response = other.patchJson("/api/applications/" + applicationId + "/stages/" + stageId,
                "{\"name\":\"Renamed\",\"notes\":\"overwritten\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownerStageNames()).containsExactly("Owner Interview");
        assertThat(JsonPath.<List<String>>read(owner.get("/api/applications/" + applicationId + "/stages").body(),
                "$[*].notes")).containsExactly("owner stage notes");
    }

    @Test
    void anotherUserCannotDeleteAStage() throws Exception {
        HttpResponse<String> response = other.deleteRequest("/api/applications/" + applicationId + "/stages/" + stageId);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownerStageNames()).containsExactly("Owner Interview");
    }

    @Test
    void anotherUserCannotReadTheJob() throws Exception {
        HttpResponse<String> response = other.get("/api/jobs/" + jobId);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(title).doesNotContain(DESCRIPTION);
    }

    @Test
    void thereIsNoGlobalJobListingOrBareJobCreation() throws Exception {
        HttpResponse<String> response = other.get("/api/jobs");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(title);
        // Creating a job without its Board ticket (which also answered "exists" for anyone's job) is gone.
        HttpResponse<String> create = other.postJson("/api/jobs", "{\"title\":\"" + title + "\",\"companyName\":\"Private Co\"}");
        assertThat(create.statusCode()).isEqualTo(404);
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title = ?", title)).isEqualTo(1);
    }

    @Test
    void anotherUserCannotAttachThemselvesToTheJobById() throws Exception {
        HttpResponse<String> response = other.postJson("/api/jobs/" + jobId + "/applications", "{}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE job_id = ?", jobId)).isEqualTo(1);
        // ...so the job stays unreadable for them afterwards.
        assertThat(other.get("/api/jobs/" + jobId).statusCode()).isEqualTo(404);
    }

    @Test
    void anotherUserCannotEvaluateTheJobAndNothingIsCalledOrDiscarded() throws Exception {
        // The other user is fully configured, so without the ownership check a
        // low score would reach the provider, create a LOW_MATCH application for
        // them and discard the owner's job description.
        assertThat(other.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Other User - Junior Designer. Figma and illustration work.")).statusCode())
                .isEqualTo(201);
        List<String> visible = JsonPath.read(other.get("/api/settings").body(), "$.visibleBoardStatuses");
        assertThat(other.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + toJson(visible) + ",\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}")
                .statusCode()).isEqualTo(200);
        assertThat(other.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode())
                .isEqualTo(200);
        fakeAiProviderState.setNextResult(new AiMatchResult(5, List.of(), List.of(), List.of()));

        for (String force : List.of("", "?force=true")) {
            HttpResponse<String> response = other.postJson("/api/jobs/" + jobId + "/match/evaluate" + force, "{}");
            assertThat(response.statusCode()).as("force=" + force).isEqualTo(404);
        }

        assertThat(fakeAiProviderState.callCount()).isZero();
        assertThat(testData.count("SELECT count(*) FROM applications WHERE job_id = ?", jobId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", jobId)).isZero();
        HttpResponse<String> job = owner.get("/api/jobs/" + jobId);
        assertThat(JsonPath.<String>read(job.body(), "$.description")).isEqualTo(DESCRIPTION);
        assertThat((Boolean) JsonPath.read(job.body(), "$.descriptionDiscarded")).isFalse();
        assertThat(ownerApplication("$.status.code")).isEqualTo("NEW");
    }

    private String ownerApplication(String path) throws Exception {
        return JsonPath.read(owner.get("/api/applications/" + applicationId).body(), path);
    }

    private List<String> ownerStageNames() throws Exception {
        return JsonPath.read(owner.get("/api/applications/" + applicationId + "/stages").body(), "$[*].name");
    }

    private int historyRows() {
        return testData.count("SELECT count(*) FROM application_status_history WHERE application_id = ?", applicationId);
    }

    private static String toJson(List<String> values) {
        return "[" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "]";
    }
}
