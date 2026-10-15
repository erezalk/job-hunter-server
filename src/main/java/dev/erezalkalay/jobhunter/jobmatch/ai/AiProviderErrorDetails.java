package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The safe, structured part of a provider error response: identifiers and
 * numbers only (HTTP status, Google {@code error.status}, quota metric/id/limit,
 * retry delay). The free-text {@code message} and everything else in the body
 * is deliberately never extracted, so nothing here can carry prompt, CV or
 * job text, and it is all fine to log.
 */
record AiProviderErrorDetails(
        int httpStatus,
        String providerStatus,
        String errorReason,
        String quotaMetric,
        String quotaId,
        String quotaLimit,
        Integer retryAfterSeconds
) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_BODY_CHARS = 16_000;
    private static final long MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_./:-]{1,160}");

    static AiProviderErrorDetails from(int httpStatus, HttpHeaders headers, String responseBody) {
        String providerStatus = null;
        String errorReason = null;
        String quotaMetric = null;
        String quotaId = null;
        String quotaLimit = null;
        Integer retryAfter = null;

        JsonNode error = parseError(responseBody);
        if (error != null) {
            providerStatus = safeToken(error.path("status").asString(null));
            for (JsonNode detail : error.path("details")) {
                String type = detail.path("@type").asString("");
                if (type.endsWith("google.rpc.ErrorInfo")) {
                    errorReason = safeToken(detail.path("reason").asString(null));
                } else if (type.endsWith("google.rpc.QuotaFailure")) {
                    for (JsonNode violation : detail.path("violations")) {
                        quotaMetric = firstNonNull(quotaMetric, safeToken(violation.path("quotaMetric").asString(null)));
                        quotaId = joinIds(quotaId, safeToken(violation.path("quotaId").asString(null)));
                        quotaLimit = firstNonNull(quotaLimit, safeToken(violation.path("quotaValue").asString(null)));
                    }
                } else if (type.endsWith("google.rpc.RetryInfo")) {
                    retryAfter = parseSeconds(detail.path("retryDelay").asString(null));
                }
            }
        }
        if (retryAfter == null && headers != null) {
            retryAfter = parseSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
        }
        return new AiProviderErrorDetails(httpStatus, providerStatus, errorReason, quotaMetric, quotaId, quotaLimit, retryAfter);
    }

    /** True only when the structured quota identifiers show a long-window or zero quota. */
    boolean indicatesLongTermQuota() {
        String ids = (quotaId == null ? "" : quotaId) + " " + (quotaMetric == null ? "" : quotaMetric);
        String normalized = ids.toLowerCase(Locale.ROOT).replace("_", "");
        return normalized.contains("perday") || normalized.contains("daily")
                || normalized.contains("permonth") || normalized.contains("monthly")
                || "0".equals(quotaLimit);
    }

    boolean hasInvalidApiKeyReason() {
        return "API_KEY_INVALID".equals(errorReason);
    }

    private static JsonNode parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            String capped = body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
            JsonNode error = MAPPER.readTree(capped).path("error");
            return error.isObject() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses "41s", "3.5s", or a plain number of seconds; rounds up; null if absent or implausible. */
    private static Integer parseSeconds(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            long seconds = (long) Math.ceil(Double.parseDouble(value));
            return seconds > 0 && seconds <= MAX_RETRY_AFTER_SECONDS ? (int) seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : null;
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : candidate;
    }

    private static String joinIds(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return current.contains(candidate) ? current : current + "," + candidate;
    }
}
