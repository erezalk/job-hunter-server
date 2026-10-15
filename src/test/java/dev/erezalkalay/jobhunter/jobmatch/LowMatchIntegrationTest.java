package dev.erezalkalay.jobhunter.jobmatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiErrorCode;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiProviderException;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class LowMatchIntegrationTest extends IntegrationTestBase {

    private static final String CV_TEXT = "Erez Alkalay - Senior Java Engineer. 10 years of Spring Boot backends.";
    private static final String DESCRIPTION = "Build backend services with Java and Spring Boot. Long description.";
    private static final int THRESHOLD = 70;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataSupport testDataSupport;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
        fakeAiProviderState.reset();

        assertThat(client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText(CV_TEXT)).statusCode()).isEqualTo(201);
        HttpResponse<String> current = client.get("/api/settings");
        List<String> visible = JsonPath.read(current.body(), "$.visibleBoardStatuses");
        assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + toJson(visible) + ",\"minimumFitScore\":" + THRESHOLD
                + ",\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}")
                .statusCode()).isEqualTo(200);
    }

    @Test
    void belowThresholdBecomesLowMatchAndKeepsOnlyLightweightData() throws Exception {
        Tracked job = trackJob();
        scriptScore(55);

        HttpResponse<String> evaluate = evaluate(job, false);

        assertThat(evaluate.statusCode()).isEqualTo(200);
        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
        HttpResponse<String> jobBody = client.get("/api/jobs/" + job.jobId());
        assertThat(JsonPath.<Object>read(jobBody.body(), "$.description")).isNull();
        assertThat((Boolean) JsonPath.read(jobBody.body(), "$.descriptionDiscarded")).isTrue();
        assertThat(JsonPath.<String>read(jobBody.body(), "$.title")).isEqualTo(job.title());
        assertThat(JsonPath.<String>read(jobBody.body(), "$.companyName")).isEqualTo("Low Match Co");
        assertThat(JsonPath.<String>read(jobBody.body(), "$.location")).isEqualTo("Remote");

        HttpResponse<String> match = client.get("/api/jobs/" + job.jobId() + "/match");
        assertThat((Integer) JsonPath.read(match.body(), "$.fitScore")).isEqualTo(55);
        assertThat(JsonPath.<String>read(match.body(), "$.provider")).isEqualTo("ANTHROPIC");
        assertThat(JsonPath.<String>read(match.body(), "$.model")).isEqualTo("test-model");
        assertThat(JsonPath.<Object>read(match.body(), "$.evaluatedAt")).isNotNull();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).isEmpty();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.missingSkills")).isEmpty();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.concerns")).isEmpty();
    }

    @Test
    void automaticTransitionIsRecordedInTheStatusHistory() throws Exception {
        Tracked job = trackJob();
        scriptScore(10);
        evaluate(job, false);

        HttpResponse<String> history = client.get("/api/applications/" + job.applicationId() + "/status-history");
        List<String> codes = JsonPath.read(history.body(), "$[*].status.code");
        List<String> notes = JsonPath.read(history.body(), "$[*].notes");
        assertThat(codes).containsExactly("NEW", "LOW_MATCH");
        assertThat(notes.get(1)).startsWith("Automatic: low AI match");
    }

    @Test
    void scoreExactlyAtOrAboveTheThresholdIsNotLowMatch() throws Exception {
        for (int score : new int[] {THRESHOLD, 92}) {
            Tracked job = trackJob();
            scriptScore(score);

            assertThat(evaluate(job, false).statusCode()).isEqualTo(200);

            assertThat(statusOf(job)).isEqualTo("NEW");
            HttpResponse<String> jobBody = client.get("/api/jobs/" + job.jobId());
            assertThat(JsonPath.<String>read(jobBody.body(), "$.description")).isEqualTo(DESCRIPTION);
            assertThat((Boolean) JsonPath.read(jobBody.body(), "$.descriptionDiscarded")).isFalse();
            HttpResponse<String> match = client.get("/api/jobs/" + job.jobId() + "/match");
            assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).containsExactly("Java");
        }
    }

    @Test
    void failedEvaluationNeverMovesToLowMatchOrDiscardsAnything() throws Exception {
        Tracked job = trackJob();
        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_RATE_LIMITED));

        assertThat(evaluate(job, false).statusCode()).isEqualTo(429);

        assertThat(statusOf(job)).isEqualTo("NEW");
        HttpResponse<String> jobBody = client.get("/api/jobs/" + job.jobId());
        assertThat(JsonPath.<String>read(jobBody.body(), "$.description")).isEqualTo(DESCRIPTION);
        assertThat((Boolean) JsonPath.read(jobBody.body(), "$.descriptionDiscarded")).isFalse();
        assertThat(client.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(404);
    }

    @Test
    void lowMatchIsCachedAndReEvaluationWithoutADescriptionIsRefusedWithoutCallingTheProvider() throws Exception {
        Tracked job = trackJob();
        scriptScore(40);
        evaluate(job, false);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        HttpResponse<String> cached = evaluate(job, false);
        assertThat(cached.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(cached.body(), "$.fitScore")).isEqualTo(40);

        HttpResponse<String> forced = evaluate(job, true);
        assertThat(forced.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(forced.body(), "$.message")).contains("description was discarded");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
    }

    @Test
    void lowMatchReEvaluatedAfterRefreshMovesToNewOnlyWhenItNowMeetsTheThreshold() throws Exception {
        Tracked job = trackJob();
        scriptScore(40);
        evaluate(job, false);

        // Re-adding the same job with a description refreshes the discarded one.
        HttpResponse<String> readd = client.postJson("/api/board/jobs", jobJson(job.title(), DESCRIPTION));
        assertThat(readd.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(readd.body(), "$.jobId")).longValue()).isEqualTo(job.jobId());
        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
        assertThat((Boolean) JsonPath.read(client.get("/api/jobs/" + job.jobId()).body(), "$.descriptionDiscarded")).isFalse();

        scriptScore(45);
        assertThat(evaluate(job, true).statusCode()).isEqualTo(200);
        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
        assertThat(JsonPath.<Object>read(client.get("/api/jobs/" + job.jobId()).body(), "$.description")).isNull();

        client.postJson("/api/board/jobs", jobJson(job.title(), DESCRIPTION));
        scriptScore(88);
        assertThat(evaluate(job, true).statusCode()).isEqualTo(200);
        assertThat(statusOf(job)).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + job.jobId()).body(), "$.description")).isEqualTo(DESCRIPTION);
        List<String> codes = JsonPath.read(
                client.get("/api/applications/" + job.applicationId() + "/status-history").body(), "$[*].status.code");
        assertThat(codes).containsExactly("NEW", "LOW_MATCH", "NEW");
    }

    @Test
    void manualWorkflowStatusIsNeverOverriddenByTheThreshold() throws Exception {
        Tracked job = trackJob();
        scriptScore(40);
        evaluate(job, false);
        client.postJson("/api/board/jobs", jobJson(job.title(), DESCRIPTION));
        assertThat(client.patchJson("/api/applications/" + job.applicationId() + "/status",
                "{\"statusCode\":\"CV_SENT\"}").statusCode()).isEqualTo(200);

        scriptScore(20);
        assertThat(evaluate(job, true).statusCode()).isEqualTo(200);

        assertThat(statusOf(job)).isEqualTo("CV_SENT");
        HttpResponse<String> jobBody = client.get("/api/jobs/" + job.jobId());
        assertThat(JsonPath.<String>read(jobBody.body(), "$.description")).isEqualTo(DESCRIPTION);
        assertThat(JsonPath.<List<String>>read(client.get("/api/jobs/" + job.jobId() + "/match").body(), "$.matchingSkills"))
                .containsExactly("Java");

        scriptScore(95);
        evaluate(job, true);
        assertThat(statusOf(job)).isEqualTo("CV_SENT");
    }

    @Test
    void lowMatchJobStillDeduplicatesAndSurvivesRediscovery() throws Exception {
        Tracked job = trackJob();
        scriptScore(30);
        evaluate(job, false);

        HttpResponse<String> readd = client.postJson("/api/board/jobs", jobJson(job.title().toUpperCase(), null));
        assertThat(((Number) JsonPath.read(readd.body(), "$.jobId")).longValue()).isEqualTo(job.jobId());
        assertThat(((Number) JsonPath.read(readd.body(), "$.id")).longValue()).isEqualTo(job.applicationId());
        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
        assertThat((Boolean) JsonPath.read(client.get("/api/jobs/" + job.jobId()).body(), "$.descriptionDiscarded")).isTrue();
    }

    @Test
    void anotherUsersJobCannotBeEvaluatedAndNothingIsRecorded() throws Exception {
        TestApiClient other = otherUser();
        HttpResponse<String> created = other.postJson("/api/board/jobs", jobJson(uniqueTitle(), DESCRIPTION));
        long jobId = ((Number) JsonPath.read(created.body(), "$.jobId")).longValue();
        scriptScore(5);

        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}").statusCode()).isEqualTo(404);

        assertThat(fakeAiProviderState.callCount()).isZero();
        List<String> statuses = JsonPath.read(client.get("/api/board").body(),
                "$[?(@.jobId==" + jobId + ")].statusCode");
        assertThat(statuses).isEmpty();
        assertThat(testDataSupport.count("SELECT count(*) FROM jobs WHERE id = ? AND description_discarded", jobId)).isZero();
        assertThat(testDataSupport.count("SELECT count(*) FROM job_matches WHERE job_id = ?", jobId)).isZero();
        assertThat(JsonPath.<List<String>>read(other.get("/api/board").body(), "$[?(@.jobId==" + jobId + ")].statusCode"))
                .containsExactly("NEW");
    }

    @Test
    void lowMatchColumnIsHiddenByDefaultAndVisibilityNeverTouchesData() throws Exception {
        assertThat(JsonPath.<List<String>>read(client.get("/api/settings").body(), "$.visibleBoardStatuses"))
                .doesNotContain("LOW_MATCH");
        List<String> statusCodes = JsonPath.read(client.get("/api/application-statuses").body(), "$[*].code");
        assertThat(statusCodes).contains("LOW_MATCH");

        Tracked job = trackJob();
        scriptScore(15);
        evaluate(job, false);

        for (String visible : new String[] {"[\"NEW\"]", "[\"NEW\",\"LOW_MATCH\"]", "[\"LOW_MATCH\"]"}) {
            assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                    + visible + ",\"minimumFitScore\":" + THRESHOLD
                    + ",\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
            assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
            assertThat((Boolean) JsonPath.read(client.get("/api/jobs/" + job.jobId()).body(), "$.descriptionDiscarded")).isTrue();
        }
        assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":[],"
                + "\"minimumFitScore\":70}").statusCode()).isEqualTo(400);
    }

    @Test
    void lowMatchStaysPrivateToTheEvaluatingUser() throws Exception {
        TestApiClient other = otherUser();
        Tracked job = trackJob();
        // The other user independently tracks the very same company + title, with a description.
        HttpResponse<String> theirs = other.postJson("/api/board/jobs", jobJson(job.title(), DESCRIPTION));
        assertThat(theirs.statusCode()).isEqualTo(201);
        long theirJob = ((Number) JsonPath.read(theirs.body(), "$.jobId")).longValue();
        long theirApplication = ((Number) JsonPath.read(theirs.body(), "$.id")).longValue();
        assertThat(theirJob).isNotEqualTo(job.jobId());
        scriptScore(10);
        evaluate(job, false);

        assertThat(statusOf(job)).isEqualTo("LOW_MATCH");
        assertThat(other.get("/api/jobs/" + job.jobId() + "/match").statusCode()).isEqualTo(404);
        assertThat(other.get("/api/board").body()).doesNotContain("\"applicationId\":" + job.applicationId() + ",");
        assertThat(other.get("/api/applications").body()).doesNotContain("\"id\":" + job.applicationId() + ",");
        // Their ticket, description and (absent) match are untouched by my low match.
        String theirJobBody = other.get("/api/jobs/" + theirJob).body();
        assertThat(JsonPath.<String>read(theirJobBody, "$.description")).isEqualTo(DESCRIPTION);
        assertThat((Boolean) JsonPath.read(theirJobBody, "$.descriptionDiscarded")).isFalse();
        assertThat(JsonPath.<String>read(other.get("/api/applications/" + theirApplication).body(), "$.status.code"))
                .isEqualTo("NEW");
        assertThat(other.get("/api/jobs/" + theirJob + "/match").statusCode()).isEqualTo(404);
    }

    private TestApiClient otherUser() throws Exception {
        testDataSupport.createUserIfMissing("low-match-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("low-match-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);
        return other;
    }

    private record Tracked(long jobId, long applicationId, String title) {
    }

    private Tracked trackJob() throws Exception {
        String title = uniqueTitle();
        HttpResponse<String> response = client.postJson("/api/board/jobs", jobJson(title, DESCRIPTION));
        assertThat(response.statusCode()).isEqualTo(201);
        return new Tracked(((Number) JsonPath.read(response.body(), "$.jobId")).longValue(),
                ((Number) JsonPath.read(response.body(), "$.id")).longValue(), title);
    }

    private static String uniqueTitle() {
        return "Low Match Engineer " + SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    private static String jobJson(String title, String description) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"Low Match Co\",\"location\":\"Remote\","
                + "\"workModel\":\"REMOTE\"" + (description != null ? ",\"description\":\"" + description + "\"" : "") + "}";
    }

    private void scriptScore(int score) {
        fakeAiProviderState.setNextResult(new AiMatchResult(score, List.of("Java"), List.of("Go"), List.of("Onsite")));
    }

    private HttpResponse<String> evaluate(Tracked job, boolean force) throws Exception {
        return client.postJson("/api/jobs/" + job.jobId() + "/match/evaluate" + (force ? "?force=true" : ""), "{}");
    }

    private String statusOf(Tracked job) throws Exception {
        return JsonPath.read(client.get("/api/applications/" + job.applicationId()).body(), "$.status.code");
    }

    private static String toJson(List<String> values) {
        return "[" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "]";
    }
}
