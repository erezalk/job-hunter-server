package dev.erezalkalay.jobhunter.jobmatch.ai;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable AI failure codes. Each carries the only text the
 * user ever sees and the HTTP status this application answers with — provider
 * responses are classified into one of these and never passed through.
 *
 * <p>Statuses deliberately avoid 401/403/404: those describe this app's own
 * session/resources, and a 401 would make the frontend treat the user as
 * logged out.
 */
public enum AiErrorCode {

    AI_INVALID_API_KEY(HttpStatus.BAD_GATEWAY, "Invalid API key. Check your API key and try again."),
    AI_PERMISSION_DENIED(HttpStatus.BAD_GATEWAY, "The API key does not have permission to use this model."),
    AI_MODEL_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "The selected AI model is unavailable. Check the selected model."),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI provider rate limit reached. Please wait and try again."),
    AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "AI provider quota exceeded. Check your API quota or try again later."),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "The AI provider is temporarily unavailable. Please try again shortly."),
    AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Could not reach the AI provider. Please try again."),
    AI_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "The AI provider returned an invalid response. Please try again."),
    AI_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "AI evaluation failed. Please try again.");

    private final HttpStatus httpStatus;
    private final String message;

    AiErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    /**
     * Only a transient provider outage is worth an automatic retry. A 429 is
     * never retried: hammering a limited free tier just burns more quota.
     */
    public boolean isRetryable() {
        return this == AI_PROVIDER_UNAVAILABLE;
    }

    /** User-facing text; provider name and retry delay refine the two 429 messages only. */
    String message(String providerName, Integer retryAfterSeconds) {
        return switch (this) {
            case AI_RATE_LIMITED -> {
                String name = providerName == null ? "AI provider" : providerName;
                yield retryAfterSeconds == null
                        ? name + " rate limit reached. Please wait and try again."
                        : name + " rate limit reached. Try again in " + describeSeconds(retryAfterSeconds) + ".";
            }
            case AI_QUOTA_EXCEEDED -> providerName == null
                    ? message
                    : providerName + " API quota exceeded. Check your API quota or try again later.";
            default -> message;
        };
    }

    private static String describeSeconds(int seconds) {
        if (seconds < 60) {
            return seconds == 1 ? "1 second" : seconds + " seconds";
        }
        int minutes = (seconds + 59) / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
