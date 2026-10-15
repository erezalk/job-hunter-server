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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class JobMatchIntegrationTest extends IntegrationTestBase {

    private static final String CV_TEXT =
            "Erez Alkalay - Senior Java Engineer. 10 years building Spring Boot and PostgreSQL backends.";

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
        HttpResponse<String> login = client.login("test@jobhunter.local", "test-password-123");
        assertThat(login.statusCode()).isEqualTo(200);

        // Isolate every test from whatever other integration test classes
        // left behind in the shared test database (singleton Testcontainers
        // Postgres — see IntegrationTestBase).
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
        clearAiSettings();
        fakeAiProviderState.reset();
    }

    @Test
    void evaluatingWithoutAnActiveCvIsRejected() throws Exception {
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("No CV Role");

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(evaluate.statusCode()).isEqualTo(400);
        assertThat(fakeAiProviderState.callCount()).isZero();
    }

    @Test
    void evaluatingWithoutAnApiKeyIsRejected() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        long jobId = createJob("No Key Role");

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(evaluate.statusCode()).isEqualTo(400);
        assertThat(fakeAiProviderState.callCount()).isZero();
    }

    @Test
    void validEvaluationIsPersistedAndReadableAfterward() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Backend Engineer");

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(evaluate.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(evaluate.body(), "$.fitScore")).isEqualTo(80);
        assertThat(JsonPath.<List<String>>read(evaluate.body(), "$.matchingSkills")).contains("Java");
        assertThat(JsonPath.<List<String>>read(evaluate.body(), "$.missingSkills")).contains("Kubernetes");
        assertThat(JsonPath.<List<String>>read(evaluate.body(), "$.concerns")).isEmpty();
        assertThat(JsonPath.<String>read(evaluate.body(), "$.provider")).isEqualTo("ANTHROPIC");
        assertThat(evaluate.body()).doesNotContain("summary");

        HttpResponse<String> reload = client.get("/api/jobs/" + jobId + "/match");
        assertThat(reload.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(reload.body(), "$.fitScore")).isEqualTo(80);
        assertThat(reload.body()).doesNotContain("summary");
    }

    @Test
    void jobWithNoEvaluationYetReturnsNotFound() throws Exception {
        long jobId = createJob("Unevaluated Role");
        HttpResponse<String> response = client.get("/api/jobs/" + jobId + "/match");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void reEvaluatingWithUnchangedInputsReusesTheCachedResult() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Reuse Role");

        client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        HttpResponse<String> second = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
    }

    @Test
    void forceReEvaluateAlwaysCallsTheProviderAgain() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Force Reuse Role");

        client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        HttpResponse<String> forced = client.postJson("/api/jobs/" + jobId + "/match/evaluate?force=true", "{}");
        assertThat(forced.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(2);
    }

    @Test
    void changingTheConfiguredModelInvalidatesReuse() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "model-a", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Model Change Role");

        client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        configureAiSettings("ANTHROPIC", "model-b", 70);
        HttpResponse<String> again = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(2);
    }

    @Test
    void replacingTheActiveCvInvalidatesReuse() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Cv Change Role");

        client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        uploadActiveCv(CV_TEXT + " Now with a new certification.");
        HttpResponse<String> again = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(2);
    }

    @Test
    void everyProviderFailureReturnsItsStableCodeAndSafeMessageAndPersistsNothing() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);

        for (AiErrorCode code : AiErrorCode.values()) {
            long jobId = createJob("Failure Role " + code.name());
            long applicationId = createApplication(jobId);
            fakeAiProviderState.setNextException(new AiProviderException(code));

            HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");

            assertThat(evaluate.statusCode()).as(code.name()).isEqualTo(code.httpStatus().value());
            assertThat(JsonPath.<String>read(evaluate.body(), "$.code")).isEqualTo(code.name());
            assertThat(JsonPath.<String>read(evaluate.body(), "$.message")).isEqualTo(code.message());
            assertThat(evaluate.body()).doesNotContain(FakeAiProviderState.VALID_API_KEY).doesNotContain("Exception");

            assertThat(client.get("/api/jobs/" + jobId + "/match").statusCode()).isEqualTo(404);
            assertThat(client.get("/api/jobs/" + jobId).statusCode()).isEqualTo(200);
            assertThat(applicationStatus(applicationId)).isEqualTo("NEW");
        }
    }

    @Test
    void userFacingMessagesMatchTheSpecifiedWording() {
        assertThat(AiErrorCode.AI_INVALID_API_KEY.message()).isEqualTo("Invalid API key. Check your API key and try again.");
        assertThat(AiErrorCode.AI_PERMISSION_DENIED.message()).isEqualTo("The API key does not have permission to use this model.");
        assertThat(AiErrorCode.AI_MODEL_UNAVAILABLE.message()).isEqualTo("The selected AI model is unavailable. Check the selected model.");
        assertThat(AiErrorCode.AI_RATE_LIMITED.message()).isEqualTo("AI provider rate limit reached. Please wait and try again.");
        assertThat(AiErrorCode.AI_QUOTA_EXCEEDED.message()).isEqualTo("AI provider quota exceeded. Check your API quota or try again later.");
        assertThat(AiErrorCode.AI_PROVIDER_UNAVAILABLE.message()).isEqualTo("The AI provider is temporarily unavailable. Please try again shortly.");
        assertThat(AiErrorCode.AI_TIMEOUT.message()).isEqualTo("Could not reach the AI provider. Please try again.");
        assertThat(AiErrorCode.AI_INVALID_RESPONSE.message()).isEqualTo("The AI provider returned an invalid response. Please try again.");
        assertThat(AiErrorCode.AI_PROVIDER_ERROR.message()).isEqualTo("AI evaluation failed. Please try again.");
    }

    @Test
    void failedReEvaluationKeepsTheExistingMatch() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Keep Existing Match");
        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}").statusCode()).isEqualTo(200);

        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_RATE_LIMITED));
        HttpResponse<String> failed = client.postJson("/api/jobs/" + jobId + "/match/evaluate?force=true", "{}");
        assertThat(failed.statusCode()).isEqualTo(429);

        HttpResponse<String> match = client.get("/api/jobs/" + jobId + "/match");
        assertThat(match.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(match.body(), "$.fitScore")).isEqualTo(80);
    }

    @Test
    void theProviderIsCalledWithoutAnOpenDatabaseTransaction() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("No Transaction Role");

        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}").statusCode()).isEqualTo(200);

        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertThat(fakeAiProviderState.transactionActiveDuringLastCall()).isFalse();
    }

    @Test
    void aMalformedFitScoreFromTheProviderChangesNothing() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Malformed Score Role");
        long applicationId = createApplication(jobId);
        // Before the strict parser, a null score became 0: LOW_MATCH plus a discarded description.
        fakeAiProviderState.setNextRawResponse("{\"fitScore\":null,\"matchingSkills\":[],\"missingSkills\":[],\"concerns\":[]}");

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");

        assertThat(evaluate.statusCode()).isEqualTo(502);
        assertThat(JsonPath.<String>read(evaluate.body(), "$.code")).isEqualTo("AI_INVALID_RESPONSE");
        assertThat(client.get("/api/jobs/" + jobId + "/match").statusCode()).isEqualTo(404);
        assertThat(applicationStatus(applicationId)).isEqualTo("NEW");
        HttpResponse<String> job = client.get("/api/jobs/" + jobId);
        assertThat((Boolean) JsonPath.read(job.body(), "$.descriptionDiscarded")).isFalse();
        assertThat(JsonPath.<String>read(job.body(), "$.description")).isNotBlank();
    }

    @Test
    void aMalformedFitScoreOnReEvaluateKeepsTheExistingMatch() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Malformed Re-evaluate Role");
        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        fakeAiProviderState.setNextRawResponse("{\"fitScore\":\"low\"}");

        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate?force=true", "{}").statusCode()).isEqualTo(502);

        HttpResponse<String> match = client.get("/api/jobs/" + jobId + "/match");
        assertThat((Integer) JsonPath.read(match.body(), "$.fitScore")).isEqualTo(80);
    }

    @Test
    void aResultIsNotStoredWhenTheInputsChangedWhileTheProviderWasEvaluating() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "model-a", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Changed During Call Role");
        long applicationId = createApplication(jobId);
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        fakeAiProviderState.setNextResult(new AiMatchResult(10, List.of(), List.of(), List.of()));
        // The user switches model while the (slow) provider call is in flight.
        fakeAiProviderState.setDuringNextCall(() ->
                testDataSupport.update("UPDATE settings SET ai_model = 'model-b' WHERE user_id = ?", user.getId()));

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");

        assertThat(evaluate.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(evaluate.body(), "$.message")).contains("Evaluate again");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertThat(client.get("/api/jobs/" + jobId + "/match").statusCode()).isEqualTo(404);
        // The low score was not applied: no LOW_MATCH, description kept.
        assertThat(applicationStatus(applicationId)).isEqualTo("NEW");
        assertThat((Boolean) JsonPath.read(client.get("/api/jobs/" + jobId).body(), "$.descriptionDiscarded")).isFalse();
    }

    @Test
    void unexpectedProviderRuntimeErrorNeverLeaksDetails() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Unexpected Failure");
        fakeAiProviderState.setNextException(new IllegalStateException("secret internal detail " + FakeAiProviderState.VALID_API_KEY));

        HttpResponse<String> evaluate = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");

        assertThat(evaluate.statusCode()).isEqualTo(500);
        assertThat(evaluate.body()).doesNotContain("secret internal detail").doesNotContain(FakeAiProviderState.VALID_API_KEY);
        assertThat(client.get("/api/jobs/" + jobId + "/match").statusCode()).isEqualTo(404);
    }

    @Test
    void testConnectionUsesTheSameClassification() throws Exception {
        configureAiSettings("ANTHROPIC", "test-model", 70);
        setApiKey("ANTHROPIC", FakeAiProviderState.VALID_API_KEY);

        HttpResponse<String> ok = client.postJson("/api/settings/ai/test-connection", "{}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(ok.body(), "$.success")).isTrue();
        assertThat(JsonPath.<String>read(ok.body(), "$.message")).isEqualTo("Connection successful.");

        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_RATE_LIMITED));
        HttpResponse<String> limited = client.postJson("/api/settings/ai/test-connection", "{}");
        assertThat((Boolean) JsonPath.read(limited.body(), "$.success")).isFalse();
        assertThat(JsonPath.<String>read(limited.body(), "$.code")).isEqualTo("AI_RATE_LIMITED");
        assertThat(JsonPath.<String>read(limited.body(), "$.message")).isEqualTo(AiErrorCode.AI_RATE_LIMITED.message());

        setApiKey("ANTHROPIC", "some-other-invalid-key");
        HttpResponse<String> invalid = client.postJson("/api/settings/ai/test-connection", "{}");
        assertThat(JsonPath.<String>read(invalid.body(), "$.code")).isEqualTo("AI_INVALID_API_KEY");
        assertThat(invalid.body()).doesNotContain("some-other-invalid-key");
    }

    @Test
    void rateLimitDelayReachesTheFrontendAndNothingIsPersistedOrChanged() throws Exception {
        uploadActiveCv(CV_TEXT);
        configureAiSettings("GEMINI", "gemini-3.8-flash", 70);
        setApiKey("GEMINI", FakeAiProviderState.VALID_API_KEY);
        long jobId = createJob("Rate Limited Job");
        long applicationId = createApplication(jobId);
        String statusBefore = applicationStatus(applicationId);
        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_RATE_LIMITED, "Gemini", 42));

        HttpResponse<String> limited = client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(JsonPath.<String>read(limited.body(), "$.code")).isEqualTo("AI_RATE_LIMITED");
        assertThat(JsonPath.<String>read(limited.body(), "$.message"))
                .isEqualTo("Gemini rate limit reached. Try again in 42 seconds.");
        assertThat((Integer) JsonPath.read(limited.body(), "$.retryAfterSeconds")).isEqualTo(42);
        assertThat(limited.headers().firstValue("Retry-After")).contains("42");
        assertThat(limited.body()).doesNotContain(FakeAiProviderState.VALID_API_KEY).doesNotContain("RESOURCE_EXHAUSTED");
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertThat(client.get("/api/jobs/" + jobId + "/match").statusCode()).isEqualTo(404);
        assertThat(applicationStatus(applicationId)).isEqualTo(statusBefore);
        assertThat(client.get("/api/jobs/" + jobId).statusCode()).isEqualTo(200);
    }

    @Test
    void confirmedQuotaHasNoRetryDelayAndTestConnectionExposesTheDelay() throws Exception {
        configureAiSettings("GEMINI", "gemini-3.8-flash", 70);
        setApiKey("GEMINI", FakeAiProviderState.VALID_API_KEY);

        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_RATE_LIMITED, "Gemini", 42));
        HttpResponse<String> limited = client.postJson("/api/settings/ai/test-connection", "{}");
        assertThat(JsonPath.<String>read(limited.body(), "$.code")).isEqualTo("AI_RATE_LIMITED");
        assertThat((Integer) JsonPath.read(limited.body(), "$.retryAfterSeconds")).isEqualTo(42);

        fakeAiProviderState.setNextException(new AiProviderException(AiErrorCode.AI_QUOTA_EXCEEDED, "Gemini", null));
        HttpResponse<String> quota = client.postJson("/api/settings/ai/test-connection", "{}");
        assertThat(JsonPath.<String>read(quota.body(), "$.message"))
                .isEqualTo("Gemini API quota exceeded. Check your API quota or try again later.");
        assertThat(quota.body()).doesNotContain("retryAfterSeconds");
    }

    // The Board ticket created together with the job (every job has exactly one).
    private long createApplication(long jobId) throws Exception {
        List<Integer> ids = JsonPath.read(client.get("/api/board").body(), "$[?(@.jobId==" + jobId + ")].applicationId");
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private String applicationStatus(long applicationId) throws Exception {
        return JsonPath.read(client.get("/api/applications/" + applicationId).body(), "$.status.code");
    }

    private void uploadActiveCv(String text) throws Exception {
        byte[] pdf = TestFiles.pdfWithText(text);
        HttpResponse<String> upload = client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf", pdf);
        assertThat(upload.statusCode()).isEqualTo(201);
    }

    private long createJob(String title) throws Exception {
        String jobJson = "{\"title\":\"" + title + "\",\"companyName\":\"Match Test Co\","
                + "\"description\":\"Build backend services with Java and Spring Boot.\","
                + "\"location\":\"Remote\",\"workModel\":\"REMOTE\"}";
        // Tracked via the Board: only a job the user has an Application for can be evaluated.
        HttpResponse<String> createJob = client.postJson("/api/board/jobs", jobJson);
        assertThat(createJob.statusCode()).isEqualTo(201);
        return Long.valueOf(JsonPath.<Integer>read(createJob.body(), "$.jobId"));
    }

    private void configureAiSettings(String provider, String model, int minimumFitScore) throws Exception {
        HttpResponse<String> current = client.get("/api/settings");
        List<String> visible = JsonPath.read(current.body(), "$.visibleBoardStatuses");
        String visibleJson = "[" + visible.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
        String update = "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\","
                + "\"visibleBoardStatuses\":" + visibleJson + ",\"minimumFitScore\":" + minimumFitScore + ","
                + "\"aiProvider\":\"" + provider + "\",\"aiModel\":\"" + model + "\"}";
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private void clearAiSettings() throws Exception {
        HttpResponse<String> current = client.get("/api/settings");
        List<String> visible = JsonPath.read(current.body(), "$.visibleBoardStatuses");
        String visibleJson = "[" + visible.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
        String update = "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\","
                + "\"visibleBoardStatuses\":" + visibleJson + ",\"minimumFitScore\":70}";
        client.putJson("/api/settings", update);
        client.deleteRequest("/api/settings/ai/api-key?provider=ANTHROPIC");
        client.deleteRequest("/api/settings/ai/api-key?provider=GEMINI");
    }

    private void setApiKey(String provider, String apiKey) throws Exception {
        String body = "{\"provider\":\"" + provider + "\",\"apiKey\":\"" + apiKey + "\"}";
        HttpResponse<String> response = client.putJson("/api/settings/ai/api-key", body);
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
