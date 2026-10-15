package dev.erezalkalay.jobhunter.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class JobEditIntegrationTest extends IntegrationTestBase {

    private static final String DESCRIPTION = "Original description of the role.";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataSupport testDataSupport;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    private TestApiClient client;
    private String company;

    private record Tracked(long jobId, long applicationId, String title) {
    }

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        company = "Edit Co " + System.nanoTime();
        fakeAiProviderState.reset();
    }

    @Test
    void ownerEditsJobFieldsAndSourceWhileSystemFieldsAreKept() throws Exception {
        Tracked job = track("Original Title", DESCRIPTION);
        String before = client.get("/api/jobs/" + job.jobId()).body();

        HttpResponse<String> response = client.patchJson("/api/jobs/" + job.jobId(), """
                {"title":"  Fixed   Title ","companyName":"%s Renamed","location":"Berlin","workModel":"HYBRID",
                 "url":"https://jobs.example.com/42","salaryMin":50,"salaryMax":70,"currency":"EUR",
                 "description":"New description","source":"Referral","externalId":"x","id":999,
                 "discoveredAt":"2001-01-01T00:00:00Z","fitScore":99}
                """.formatted(company));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Fixed Title");
        assertThat(JsonPath.<String>read(response.body(), "$.companyName")).isEqualTo(company + " Renamed");
        assertThat(JsonPath.<String>read(response.body(), "$.location")).isEqualTo("Berlin");
        assertThat(JsonPath.<String>read(response.body(), "$.workModel")).isEqualTo("HYBRID");
        assertThat(JsonPath.<String>read(response.body(), "$.url")).isEqualTo("https://jobs.example.com/42");
        assertThat(JsonPath.<String>read(response.body(), "$.description")).isEqualTo("New description");
        assertThat((Integer) JsonPath.read(response.body(), "$.salaryMax")).isEqualTo(70);

        HttpResponse<String> reloaded = client.get("/api/jobs/" + job.jobId());
        assertThat(JsonPath.<String>read(reloaded.body(), "$.title")).isEqualTo("Fixed Title");
        assertThat(((Number) JsonPath.read(reloaded.body(), "$.id")).longValue()).isEqualTo(job.jobId());
        // Source is user-editable free text; the other system fields are not.
        assertThat(JsonPath.<String>read(reloaded.body(), "$.source")).isEqualTo("Referral");
        for (String system : new String[] {"discoveredAt", "createdAt"}) {
            assertThat(JsonPath.<Object>read(reloaded.body(), "$." + system))
                    .isEqualTo(JsonPath.<Object>read(before, "$." + system));
        }
        assertThat(JsonPath.<Object>read(reloaded.body(), "$.fitScore")).isNull();
    }

    @Test
    void anotherUserAndAnonymousCannotEdit() throws Exception {
        testDataSupport.createUserIfMissing("job-edit-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("job-edit-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);
        Tracked job = track("Private Role", DESCRIPTION);

        assertThat(other.patchJson("/api/jobs/" + job.jobId(), body("Stolen", company, null)).statusCode())
                .isEqualTo(404);
        assertThat(new TestApiClient(port).patchJson("/api/jobs/" + job.jobId(), body("Anon", company, null))
                .statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + job.jobId()).body(), "$.title"))
                .isEqualTo("Private Role");
    }

    @Test
    void invalidInputIsRejectedWithFieldErrors() throws Exception {
        Tracked job = track("Valid Role", DESCRIPTION);

        HttpResponse<String> blankTitle = client.patchJson("/api/jobs/" + job.jobId(), body("   ", company, null));
        assertThat(blankTitle.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(blankTitle.body(), "$.fieldErrors[*].field")).contains("title");

        HttpResponse<String> badUrl = client.patchJson("/api/jobs/" + job.jobId(),
                body("Valid Role", company, "not a url"));
        assertThat(badUrl.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(badUrl.body(), "$.fieldErrors[*].field")).contains("url");

        assertThat(client.patchJson("/api/jobs/" + job.jobId(), body("Valid Role", company, "")).statusCode())
                .isEqualTo(200);
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + job.jobId()).body(), "$.title"))
                .isEqualTo("Valid Role");
    }

    @Test
    void identityKeysAreRecomputedWithTheSharedNormalization() throws Exception {
        Tracked job = track("Old Title", DESCRIPTION);
        assertThat(keyCount(company, "Old Title")).isEqualTo(1);

        assertThat(client.patchJson("/api/jobs/" + job.jobId(), body("  New   TITLE ", company, null)).statusCode())
                .isEqualTo(200);

        assertThat(keyCount(company, "Old Title")).isZero();
        assertThat(keyCount(company, "new title")).isEqualTo(1);
        // The freed identity can be used again; the new one now deduplicates.
        assertThat(client.postJson("/api/board/jobs", newJob("Old Title", DESCRIPTION)).statusCode()).isEqualTo(201);
        HttpResponse<String> again = client.postJson("/api/board/jobs", newJob("new title", DESCRIPTION));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(again.body(), "$.jobId")).longValue()).isEqualTo(job.jobId());
    }

    @Test
    void editThatCollidesWithAnotherJobIsRejectedAndNothingChanges() throws Exception {
        Tracked first = track("Taken Title", DESCRIPTION);
        Tracked second = track("Other Title", DESCRIPTION);

        HttpResponse<String> clash = client.patchJson("/api/jobs/" + second.jobId(),
                body("TAKEN  title", company.toUpperCase(), null));

        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(clash.body(), "$.message")).contains("already exists");
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + second.jobId()).body(), "$.title"))
                .isEqualTo("Other Title");
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + first.jobId()).body(), "$.title"))
                .isEqualTo("Taken Title");
        // Re-saving a job with only a case/whitespace change to its own identity is not a conflict.
        assertThat(client.patchJson("/api/jobs/" + first.jobId(), body("taken title", company, null)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void applicationDataSurvivesAJobEdit() throws Exception {
        Tracked job = track("Keep Application", DESCRIPTION);
        client.patchJson("/api/applications/" + job.applicationId() + "/status", "{\"statusCode\":\"CV_SENT\"}");
        client.patchJson("/api/applications/" + job.applicationId() + "/notes", "{\"notes\":\"call on Monday\"}");
        client.postJson("/api/applications/" + job.applicationId() + "/stages", "{\"name\":\"Phone screen\"}");

        assertThat(client.patchJson("/api/jobs/" + job.jobId(), body("Renamed Role", company + " 2", null))
                .statusCode()).isEqualTo(200);

        HttpResponse<String> application = client.get("/api/applications/" + job.applicationId());
        assertThat(JsonPath.<String>read(application.body(), "$.status.code")).isEqualTo("CV_SENT");
        assertThat(JsonPath.<String>read(application.body(), "$.notes")).isEqualTo("call on Monday");
        assertThat(JsonPath.<List<String>>read(
                client.get("/api/applications/" + job.applicationId() + "/status-history").body(),
                "$[*].status.code")).containsExactly("NEW", "CV_SENT");
        assertThat(JsonPath.<List<String>>read(
                client.get("/api/applications/" + job.applicationId() + "/stages").body(), "$[*].name"))
                .containsExactly("Phone screen");
    }

    @Test
    void aiRelevantEditInvalidatesTheMatchWithoutCallingTheProvider() throws Exception {
        Tracked job = track("Stale Role", DESCRIPTION);
        prepareAi();
        fakeAiProviderState.setNextResult(new AiMatchResult(85, List.of("Java"), List.of(), List.of()));
        assertThat(client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        assertThat(client.patchJson("/api/jobs/" + job.jobId(),
                body("Stale Role", company, null, "A completely different description")).statusCode()).isEqualTo(200);

        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertThat(client.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(404);
        assertThat(fitScoreOnBoard(job)).isNull();
        assertThat(JsonPath.<String>read(client.get("/api/applications/" + job.applicationId()).body(),
                "$.status.code")).isEqualTo("NEW");
    }

    @Test
    void editsOutsideTheAiInputKeepTheMatchAndItsCache() throws Exception {
        Tracked job = track("Stable Role", DESCRIPTION);
        prepareAi();
        fakeAiProviderState.setNextResult(new AiMatchResult(85, List.of("Java"), List.of(), List.of()));
        client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate", "{}");

        HttpResponse<String> edit = client.patchJson("/api/jobs/" + job.jobId(), """
                {"title":"Stable Role","companyName":"%s","location":"Remote","description":"%s","url":"https://jobs.example.com/9",
                 "salaryMin":10,"salaryMax":20,"currency":"USD"}""".formatted(company, DESCRIPTION));

        assertThat(edit.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(edit.body(), "$.fitScore")).intValue()).isEqualTo(85);
        assertThat(client.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(200);
        // The cached evaluation is still valid, so a normal evaluate makes no provider call.
        assertThat(client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
    }

    @Test
    void lowMatchTicketCanGetADescriptionWithoutAutomaticStatusChangeOrEvaluation() throws Exception {
        Tracked job = track("Low Role", DESCRIPTION);
        prepareAi();
        fakeAiProviderState.setNextResult(new AiMatchResult(30, List.of("Java"), List.of("Go"), List.of()));
        client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate", "{}");
        HttpResponse<String> discarded = client.get("/api/jobs/" + job.jobId());
        assertThat((Boolean) JsonPath.read(discarded.body(), "$.descriptionDiscarded")).isTrue();
        assertThat(status(job)).isEqualTo("LOW_MATCH");

        // A URL-only edit changes nothing about the discarded state or the stored score.
        client.patchJson("/api/jobs/" + job.jobId(), "{\"title\":\"Low Role\",\"companyName\":\"" + company
                + "\",\"location\":\"Remote\",\"url\":\"https://jobs.example.com/low\"}");
        assertThat((Boolean) JsonPath.read(client.get("/api/jobs/" + job.jobId()).body(), "$.descriptionDiscarded"))
                .isTrue();
        assertThat(client.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(200);

        HttpResponse<String> edit = client.patchJson("/api/jobs/" + job.jobId(),
                body("Low Role", company, null, "A fresh, complete description"));

        assertThat(edit.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(edit.body(), "$.descriptionDiscarded")).isFalse();
        assertThat(JsonPath.<String>read(edit.body(), "$.description")).isEqualTo("A fresh, complete description");
        assertThat(status(job)).isEqualTo("LOW_MATCH");
        assertThat(client.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(404);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        // Only an explicit evaluation can move it, via the normal threshold rule.
        fakeAiProviderState.setNextResult(new AiMatchResult(90, List.of("Java"), List.of(), List.of()));
        assertThat(client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(status(job)).isEqualTo("NEW");
    }

    @Test
    void boardIsTheOwnerScopedSourceOfLocationSuggestions() throws Exception {
        testDataSupport.createUserIfMissing("job-edit-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("job-edit-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);
        String tag = "Loc" + System.nanoTime();
        long archived = trackAt("Archived Role", tag + "-Archived");
        trackAt("Low Role", tag + "-Low");
        client.patchJson("/api/applications/" + archived + "/status", "{\"statusCode\":\"ARCHIVED\"}");
        HttpResponse<String> theirs = other.postJson("/api/board/jobs", "{\"title\":\"Their Role\",\"companyName\":\""
                + company + " Other\",\"location\":\"" + tag + "-Theirs\"}");
        assertThat(theirs.statusCode()).isEqualTo(201);

        List<String> mine = JsonPath.read(client.get("/api/board").body(), "$[*].location");
        List<String> others = JsonPath.read(other.get("/api/board").body(), "$[*].location");

        assertThat(mine).contains(tag + "-Archived", tag + "-Low").doesNotContain(tag + "-Theirs");
        assertThat(others).contains(tag + "-Theirs").doesNotContain(tag + "-Archived", tag + "-Low");
    }

    private long trackAt(String title, String location) throws Exception {
        HttpResponse<String> response = client.postJson("/api/board/jobs", "{\"title\":\"" + title + "\",\"companyName\":\""
                + company + "\",\"location\":\"" + location + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Number) JsonPath.read(response.body(), "$.id")).longValue();
    }

    private Tracked track(String title, String description) throws Exception {
        HttpResponse<String> response = client.postJson("/api/board/jobs", newJob(title, description));
        assertThat(response.statusCode()).isEqualTo(201);
        return new Tracked(((Number) JsonPath.read(response.body(), "$.jobId")).longValue(),
                ((Number) JsonPath.read(response.body(), "$.id")).longValue(), title);
    }

    private String newJob(String title, String description) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\",\"location\":\"Remote\","
                + "\"description\":\"" + description + "\"}";
    }

    private static String body(String title, String companyName, String url) {
        return body(title, companyName, url, DESCRIPTION);
    }

    private static String body(String title, String companyName, String url, String description) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + companyName + "\",\"location\":\"Remote\","
                + "\"description\":\"" + description + "\"" + (url != null ? ",\"url\":\"" + url + "\"" : "") + "}";
    }

    private int keyCount(String companyName, String title) {
        return testDataSupport.count("select count(*) from jobs where company_key = ? and title_key = ?",
                companyName.trim().replaceAll("\\s+", " ").toLowerCase(), title.trim().replaceAll("\\s+", " ").toLowerCase());
    }

    private String status(Tracked job) throws Exception {
        return JsonPath.read(client.get("/api/applications/" + job.applicationId()).body(), "$.status.code");
    }

    private Object fitScoreOnBoard(Tracked job) throws Exception {
        List<Object> scores = JsonPath.read(client.get("/api/board").body(),
                "$[?(@.jobId==" + job.jobId() + ")].fitScore");
        return scores.get(0);
    }

    private void prepareAi() throws Exception {
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
        assertThat(client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Erez Alkalay - Senior Java Engineer with 10 years of Spring Boot.")).statusCode())
                .isEqualTo(201);
        HttpResponse<String> current = client.get("/api/settings");
        List<String> visible = JsonPath.read(current.body(), "$.visibleBoardStatuses");
        String visibleJson = "[" + String.join(",", visible.stream().map(v -> "\"" + v + "\"").toList()) + "]";
        assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + visibleJson + ",\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}")
                .statusCode()).isEqualTo(200);
        assertThat(client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}")
                .statusCode()).isEqualTo(200);
    }
}
