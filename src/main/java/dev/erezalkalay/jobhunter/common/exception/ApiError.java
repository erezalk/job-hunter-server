package dev.erezalkalay.jobhunter.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code,
        String path,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, null, path, null, List.of());
    }

    public static ApiError withCode(int status, String error, String message, String code, String path,
                                    Integer retryAfterSeconds) {
        return new ApiError(Instant.now(), status, error, message, code, path, retryAfterSeconds, List.of());
    }

    public static ApiError ofFieldErrors(int status, String error, String path, Map<String, String> errors) {
        List<FieldError> fieldErrors = errors.entrySet().stream()
                .map(e -> new FieldError(e.getKey(), e.getValue()))
                .toList();
        return new ApiError(Instant.now(), status, error, "Validation failed", null, path, null, fieldErrors);
    }
}
