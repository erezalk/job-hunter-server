package dev.erezalkalay.jobhunter.jobmatch.ai;

/**
 * A provider call failed. The message always comes from {@link AiErrorCode}
 * (optionally naming the provider and a provider-supplied retry delay) and is
 * safe to show the user — never a raw stack trace, provider response or API
 * key. Diagnostic detail is logged separately (without secrets).
 */
public class AiProviderException extends RuntimeException {

    private final AiErrorCode code;
    private final Integer retryAfterSeconds;

    public AiProviderException(AiErrorCode code) {
        this(code, null, null);
    }

    public AiProviderException(AiErrorCode code, String providerName, Integer retryAfterSeconds) {
        super(code.message(providerName, retryAfterSeconds));
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AiErrorCode getCode() {
        return code;
    }

    /** Seconds the provider said to wait, or null when it gave no (reliable) delay. */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
