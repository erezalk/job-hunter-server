package dev.erezalkalay.jobhunter.jobmatch.ai;

/**
 * One AI provider capable of scoring a CV against a job posting. The rest of
 * the application depends only on this interface (via
 * {@link AiJobMatchProviderRegistry}), never on a specific provider —
 * {@link AnthropicJobMatchProvider} and {@link GeminiJobMatchProvider} are
 * the only two implementations today, and adding a third later means adding
 * one more implementation, not touching callers.
 */
public interface AiJobMatchProvider {

    ProviderId id();

    /**
     * Scores {@code request} using the given API key/model. Throws
     * {@link AiProviderException} for any failure (auth, rate limit,
     * timeout, malformed response, ...) — never returns a fabricated score.
     */
    AiMatchResult evaluate(AiMatchRequest request, String apiKey, String model);

    /**
     * Makes one minimal, inexpensive call to verify the API key/model work.
     * Does not throw — failures are reported in the result.
     */
    ConnectionTestResult testConnection(String apiKey, String model);
}
