package dev.erezalkalay.jobhunter.jobmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the real Gemini provider against a mock HTTP server so the number
 * and kind of requests one action sends is verified at the wire level.
 */
class GeminiJobMatchProviderTest {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String MODEL = "gemini-3.8-flash";
    private static final String KEY = "AIza-test-key-not-real";
    private static final String GENERATE_URL = BASE + "/" + MODEL + ":generateContent";
    private static final String MODEL_URL = BASE + "/" + MODEL;

    private static final String GENERATION_OK = """
            {"candidates":[{"content":{"parts":[{"text":
            "{\\"fitScore\\":77,\\"matchingSkills\\":[\\"Java\\"],\\"missingSkills\\":[],\\"concerns\\":[]}"}]}}]}
            """;
    private static final String RATE_LIMIT_429 = """
            {"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED","details":[
             {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
               {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                "quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaValue":"5"}]},
             {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"41s"}]}}
            """;
    private static final AiMatchRequest REQUEST =
            new AiMatchRequest("CV text", "Backend Engineer", "Acme", "Remote", "REMOTE", "Build services");

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GeminiJobMatchProvider provider = new GeminiJobMatchProvider(
            builder.build(), new JobMatchPromptBuilder(), new AiResponseParser(JsonMapper.builder().build()),
            new AiCallExecutor(1, 1, 50));

    private AiProviderException evaluateExpectingFailure() {
        try {
            provider.evaluate(REQUEST, KEY, MODEL);
        } catch (AiProviderException e) {
            return e;
        }
        throw new AssertionError("expected failure");
    }

    @Test
    void successfulEvaluationSendsExactlyOneGenerationRequest() {
        server.expect(once(), requestTo(GENERATE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(content().string(not(containsString("summary"))))
                .andRespond(withSuccess(GENERATION_OK, MediaType.APPLICATION_JSON));

        assertThat(provider.evaluate(REQUEST, KEY, MODEL).fitScore()).isEqualTo(77);
        server.verify();
    }

    @Test
    void transientUnavailableThenSuccessIsTwoRequests() {
        server.expect(once(), requestTo(GENERATE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withSuccess(GENERATION_OK, MediaType.APPLICATION_JSON));

        assertThat(provider.evaluate(REQUEST, KEY, MODEL).fitScore()).isEqualTo(77);
        server.verify();
    }

    @Test
    void repeatedUnavailableStopsAtTwoRequests() {
        server.expect(twice(), requestTo(GENERATE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(evaluateExpectingFailure().getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        server.verify();
    }

    @Test
    void rateLimitIsExactlyOneRequestWithTheProviderRetryDelay() {
        server.expect(once(), requestTo(GENERATE_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body(RATE_LIMIT_429));

        AiProviderException e = evaluateExpectingFailure();
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(41);
        server.verify();
    }

    @Test
    void authModelAndPermissionFailuresAreExactlyOneRequest() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND}) {
            server.reset();
            server.expect(once(), requestTo(GENERATE_URL)).andRespond(withStatus(status));
            evaluateExpectingFailure();
            server.verify();
        }
    }

    @Test
    void testConnectionValidatesKeyAndModelWithoutGeneratingAnything() {
        server.expect(once(), requestTo(MODEL_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("x-goog-api-key", KEY))
                .andRespond(withSuccess("""
                        {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]}
                        """, MediaType.APPLICATION_JSON));

        ConnectionTestResult result = provider.testConnection(KEY, MODEL);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Connection successful.");
        server.verify();
    }

    @Test
    void testConnectionReportsAnInvalidKeyFromGoogle() {
        server.expect(once(), requestTo(MODEL_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[
                         {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}
                        """));

        ConnectionTestResult result = provider.testConnection(KEY, MODEL);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("AI_INVALID_API_KEY");
        assertThat(result.message()).doesNotContain(KEY);
        server.verify();
    }

    @Test
    void testConnectionReportsAModelThisKeyCannotUse() {
        server.expect(once(), requestTo(MODEL_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.testConnection(KEY, MODEL).code()).isEqualTo("AI_MODEL_UNAVAILABLE");
        server.verify();

        server.reset();
        server.expect(once(), requestTo(MODEL_URL)).andRespond(withSuccess(
                "{\"name\":\"models/x\",\"supportedGenerationMethods\":[\"embedContent\"]}", MediaType.APPLICATION_JSON));
        assertThat(provider.testConnection(KEY, MODEL).code()).isEqualTo("AI_MODEL_UNAVAILABLE");
        server.verify();
    }

    @Test
    void testConnectionNeverRetries() {
        server.expect(once(), requestTo(MODEL_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(provider.testConnection(KEY, MODEL).code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        server.verify();

        server.reset();
        server.expect(once(), requestTo(MODEL_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body(RATE_LIMIT_429));
        ConnectionTestResult limited = provider.testConnection(KEY, MODEL);
        assertThat(limited.code()).isEqualTo("AI_RATE_LIMITED");
        assertThat(limited.retryAfterSeconds()).isEqualTo(41);
        server.verify();
    }
}
