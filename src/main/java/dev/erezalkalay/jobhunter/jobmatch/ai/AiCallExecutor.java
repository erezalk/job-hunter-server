package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs one outbound provider call and turns every failure into an
 * {@link AiProviderException} with a stable {@link AiErrorCode}. This is the
 * only place AI calls are retried.
 *
 * <p>{@link #execute} retries a transient provider outage ({@code AI_PROVIDER_UNAVAILABLE},
 * i.e. 502/503/504/529) at most {@code app.ai.max-retries} times (default 1, so
 * at most 2 requests) after a short jittered backoff, or after the provider's
 * own Retry-After/RetryInfo delay when it gives one that is not too long.
 * Everything else — 429 (rate limit or quota), invalid key, permission, model,
 * timeout, invalid response — fails immediately. {@link #executeOnce} never
 * retries (Test Connection).
 *
 * <p>Logs carry provider, model, HTTP status and safe structured metadata only
 * — never the API key, prompt, CV/job text or the raw provider response body.
 */
@Component
public class AiCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiCallExecutor.class);

    private final int maxRetries;
    private final long initialBackoffMs;
    private final long maxWaitMs;

    public AiCallExecutor(
            @Value("${app.ai.max-retries:1}") int maxRetries,
            @Value("${app.ai.retry-initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${app.ai.retry-max-wait-ms:5000}") long maxWaitMs
    ) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxWaitMs = Math.max(0, maxWaitMs);
    }

    public <T> T execute(String provider, String model, Supplier<T> call) {
        return run(provider, model, call, maxRetries);
    }

    public <T> T executeOnce(String provider, String model, Supplier<T> call) {
        return run(provider, model, call, 0);
    }

    private <T> T run(String provider, String model, Supplier<T> call, int retries) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (AiProviderException e) {
                throw e;
            } catch (RuntimeException e) {
                AiProviderException failure = classifyFailure(provider, model, e);
                if (!failure.getCode().isRetryable() || attempt >= retries) {
                    throw failure;
                }
                long waitMs = retryWaitMs(attempt, failure.getRetryAfterSeconds());
                if (waitMs > maxWaitMs || !sleep(waitMs)) {
                    throw failure;
                }
                log.info("Retrying AI provider call: provider={} model={} attempt={} after {} ms",
                        provider, model, attempt + 2, waitMs);
            }
        }
    }

    private long retryWaitMs(int attempt, Integer providerRetryAfterSeconds) {
        if (providerRetryAfterSeconds != null) {
            return providerRetryAfterSeconds * 1000L;
        }
        long base = initialBackoffMs << attempt;
        return base / 2 + ThreadLocalRandom.current().nextLong(base + 1);
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private AiProviderException classifyFailure(String provider, String model, RuntimeException e) {
        if (e instanceof RestClientResponseException http) {
            String body = http.getResponseBodyAsString();
            AiProviderErrorDetails details =
                    AiProviderErrorDetails.from(http.getStatusCode().value(), http.getResponseHeaders(), body);
            AiErrorCode code = classify(details, body);
            // A long-term quota has no meaningful "try again in N seconds".
            Integer retryAfter = code == AiErrorCode.AI_QUOTA_EXCEEDED ? null : details.retryAfterSeconds();
            log.warn("AI provider call failed: provider={} model={} httpStatus={} providerStatus={} code={} "
                            + "quotaMetric={} quotaId={} quotaLimit={} retryAfterSeconds={}",
                    provider, model, details.httpStatus(), details.providerStatus(), code,
                    details.quotaMetric(), details.quotaId(), details.quotaLimit(), details.retryAfterSeconds());
            return new AiProviderException(code, provider, retryAfter);
        }
        if (e instanceof ResourceAccessException) {
            log.warn("AI provider call failed: provider={} model={} httpStatus=none code={} (timeout/network)",
                    provider, model, AiErrorCode.AI_TIMEOUT);
            return new AiProviderException(AiErrorCode.AI_TIMEOUT);
        }
        if (e instanceof RestClientException) {
            log.warn("AI provider call failed: provider={} model={} httpStatus=none code={} (unreadable response)",
                    provider, model, AiErrorCode.AI_INVALID_RESPONSE);
            return new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }
        log.error("Unexpected error calling AI provider: provider={} model={} code={}",
                provider, model, AiErrorCode.AI_PROVIDER_ERROR, e);
        return new AiProviderException(AiErrorCode.AI_PROVIDER_ERROR);
    }

    /**
     * 429 is a rate limit unless the structured quota details show a long-term
     * (per-day/month or zero) quota; missing or ambiguous details mean rate limit.
     * The body is consulted as free text only for Anthropic's unstructured
     * "credit balance is too low" 400.
     */
    static AiErrorCode classify(AiProviderErrorDetails details, String responseBody) {
        return switch (details.httpStatus()) {
            case 401 -> AiErrorCode.AI_INVALID_API_KEY;
            case 403 -> AiErrorCode.AI_PERMISSION_DENIED;
            case 404 -> AiErrorCode.AI_MODEL_UNAVAILABLE;
            case 408 -> AiErrorCode.AI_TIMEOUT;
            case 429 -> details.indicatesLongTermQuota() ? AiErrorCode.AI_QUOTA_EXCEEDED : AiErrorCode.AI_RATE_LIMITED;
            case 502, 503, 504, 529 -> AiErrorCode.AI_PROVIDER_UNAVAILABLE;
            case 400 -> {
                if (details.hasInvalidApiKeyReason()) {
                    yield AiErrorCode.AI_INVALID_API_KEY;
                }
                boolean creditsExhausted = responseBody != null
                        && responseBody.toLowerCase(Locale.ROOT).contains("credit balance is too low");
                yield creditsExhausted ? AiErrorCode.AI_QUOTA_EXCEEDED : AiErrorCode.AI_PROVIDER_ERROR;
            }
            default -> AiErrorCode.AI_PROVIDER_ERROR;
        };
    }
}
