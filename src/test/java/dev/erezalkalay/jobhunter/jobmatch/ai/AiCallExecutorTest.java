package dev.erezalkalay.jobhunter.jobmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class AiCallExecutorTest {

    private static final String SECRET = "sk-super-secret-key";

    private static final String PER_MINUTE_429 = geminiQuotaBody(
            "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "5", "41s");
    private static final String TOKENS_PER_MINUTE_429 = geminiQuotaBody(
            "GenerateContentInputTokensPerModelPerMinute-FreeTier", "250000", "12.2s");
    private static final String PER_DAY_429 = geminiQuotaBody(
            "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "41s");

    // 1 retry max, tiny backoff, never waits longer than 50 ms.
    private final AiCallExecutor executor = new AiCallExecutor(1, 1, 50);

    private static String geminiQuotaBody(String quotaId, String limit, String retryDelay) {
        return """
                {"error":{"code":429,
                 "message":"You exceeded your current quota, please check your plan and billing details. %s",
                 "status":"RESOURCE_EXHAUSTED",
                 "details":[
                  {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                     "quotaId":"%s","quotaDimensions":{"model":"gemini-3.8-flash"},"quotaValue":"%s"}]},
                  {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"%s"}
                 ]}}
                """.formatted(SECRET, quotaId, limit, retryDelay);
    }

    private static RestClientResponseException http(int status, String body) {
        return http(status, body, new HttpHeaders());
    }

    private static RestClientResponseException http(int status, String body, HttpHeaders headers) {
        HttpStatus code = HttpStatus.valueOf(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return code.is5xxServerError()
                ? HttpServerErrorException.create(code, code.getReasonPhrase(), headers, bytes, StandardCharsets.UTF_8)
                : HttpClientErrorException.create(code, code.getReasonPhrase(), headers, bytes, StandardCharsets.UTF_8);
    }

    private static AiProviderException failureOf(Supplier<Object> call) {
        try {
            call.get();
        } catch (AiProviderException e) {
            return e;
        }
        throw new AssertionError("expected an AiProviderException");
    }

    /** Runs a call that always fails with {@code failure}; returns the attempt count and the thrown exception. */
    private record Outcome(int attempts, AiProviderException failure) {
    }

    private Outcome alwaysFailing(RuntimeException failure) {
        AtomicInteger calls = new AtomicInteger();
        AiProviderException e = failureOf(() -> executor.execute("Gemini", "m", () -> {
            calls.incrementAndGet();
            throw failure;
        }));
        return new Outcome(calls.get(), e);
    }

    @Test
    void successfulCallMakesExactlyOneRequest() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute("Gemini", "m", () -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void transientUnavailableIsRetriedOnceThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute("Gemini", "m", () -> {
            if (calls.incrementAndGet() == 1) {
                throw http(503, "");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void repeatedUnavailableStopsAfterTheSecondAttempt() {
        Outcome outcome = alwaysFailing(http(503, ""));
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.failure().getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void rateLimitIsNeverRetried() {
        for (String body : new String[] {PER_MINUTE_429, PER_DAY_429, "", "not json"}) {
            assertThat(alwaysFailing(http(429, body)).attempts()).isEqualTo(1);
        }
    }

    @Test
    void permanentFailuresAreNeverRetried() {
        for (int status : new int[] {400, 401, 403, 404}) {
            assertThat(alwaysFailing(http(status, "{}")).attempts()).as("status " + status).isEqualTo(1);
        }
    }

    @Test
    void timeoutInvalidResponseAndUnexpectedErrorsAreNeverRetried() {
        assertThat(alwaysFailing(new ResourceAccessException("I/O", new IOException("timed out"))).attempts()).isEqualTo(1);
        assertThat(alwaysFailing(new RestClientException("unreadable")).attempts()).isEqualTo(1);
        assertThat(alwaysFailing(new IllegalStateException("boom")).attempts()).isEqualTo(1);
    }

    @Test
    void executeOnceNeverRetriesEvenATransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        AiProviderException e = failureOf(() -> executor.executeOnce("Gemini", "m", () -> {
            calls.incrementAndGet();
            throw http(503, "");
        }));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void providerRetryDelayLongerThanTheMaxWaitIsNotWaitedOut() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        Outcome outcome = alwaysFailing(http(503, "", headers));
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.failure().getRetryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void perMinuteRequestQuotaIsARateLimitEvenThoughTheTextSaysQuota() {
        AiProviderException e = alwaysFailing(http(429, PER_MINUTE_429)).failure();
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
        assertThat(e.getMessage()).isEqualTo("Gemini rate limit reached. Try again in 41 seconds.");
        assertThat(e.getRetryAfterSeconds()).isEqualTo(41);
    }

    @Test
    void perMinuteTokenQuotaIsARateLimitAndRetryDelayRoundsUp() {
        AiProviderException e = alwaysFailing(http(429, TOKENS_PER_MINUTE_429)).failure();
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(13);
    }

    @Test
    void perDayQuotaIsQuotaExceededWithoutARetryPromise() {
        AiProviderException e = alwaysFailing(http(429, PER_DAY_429)).failure();
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_QUOTA_EXCEEDED);
        assertThat(e.getMessage()).isEqualTo("Gemini API quota exceeded. Check your API quota or try again later.");
        assertThat(e.getRetryAfterSeconds()).isNull();
    }

    @Test
    void zeroQuotaLimitIsQuotaExceeded() {
        String body = geminiQuotaBody("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "0", "10s");
        assertThat(alwaysFailing(http(429, body)).failure().getCode()).isEqualTo(AiErrorCode.AI_QUOTA_EXCEEDED);
    }

    @Test
    void ambiguousOrUnstructuredRateLimitResponsesPreferRateLimited() {
        String quotaWordingOnly = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\","
                + "\"message\":\"You exceeded your current quota, check your plan and billing details.\"}}";
        for (String body : new String[] {quotaWordingOnly, "", "<html>Too Many Requests</html>", "{\"error\":{}}"}) {
            AiProviderException e = alwaysFailing(http(429, body)).failure();
            assertThat(e.getCode()).as(body).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
            assertThat(e.getMessage()).isEqualTo("Gemini rate limit reached. Please wait and try again.");
        }
    }

    @Test
    void retryAfterHeaderIsUsedWhenThereIsNoRetryInfo() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "90");
        AiProviderException e = alwaysFailing(http(429, "{}", headers)).failure();
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(90);
        assertThat(e.getMessage()).isEqualTo("Gemini rate limit reached. Try again in 2 minutes.");
    }

    @Test
    void structuredInvalidApiKeyOnAGemini400IsDetected() {
        String body = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"details\":["
                + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\"}]}}";
        assertThat(alwaysFailing(http(400, body)).failure().getCode()).isEqualTo(AiErrorCode.AI_INVALID_API_KEY);
        assertThat(alwaysFailing(http(400, "{}")).failure().getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_ERROR);
    }

    @Test
    void statusesMapToStableCodes() {
        assertThat(alwaysFailing(http(401, "")).failure().getCode()).isEqualTo(AiErrorCode.AI_INVALID_API_KEY);
        assertThat(alwaysFailing(http(403, "")).failure().getCode()).isEqualTo(AiErrorCode.AI_PERMISSION_DENIED);
        assertThat(alwaysFailing(http(404, "")).failure().getCode()).isEqualTo(AiErrorCode.AI_MODEL_UNAVAILABLE);
        assertThat(alwaysFailing(new ResourceAccessException("I/O", new IOException("x"))).failure().getCode())
                .isEqualTo(AiErrorCode.AI_TIMEOUT);
        assertThat(alwaysFailing(new RestClientException("x")).failure().getCode()).isEqualTo(AiErrorCode.AI_INVALID_RESPONSE);
        assertThat(alwaysFailing(new IllegalStateException("x")).failure().getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_ERROR);
        assertThat(alwaysFailing(http(418, "")).failure().getCode()).isEqualTo(AiErrorCode.AI_PROVIDER_ERROR);
    }

    @Test
    void neverExposesProviderBodyKeyOrInternalsInTheException() {
        for (RuntimeException raw : new RuntimeException[] {
                http(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + SECRET + "\"}}"),
                http(429, PER_MINUTE_429),
                http(429, PER_DAY_429),
                http(503, "overloaded " + SECRET),
                new IllegalStateException("internal " + SECRET)}) {
            AiProviderException e = alwaysFailing(raw).failure();
            assertThat(e).hasNoCause();
            assertThat(e.toString()).doesNotContain(SECRET).doesNotContain("Incorrect").doesNotContain("billing")
                    .doesNotContain("RESOURCE_EXHAUSTED").doesNotContain("FreeTier");
        }
    }
}
