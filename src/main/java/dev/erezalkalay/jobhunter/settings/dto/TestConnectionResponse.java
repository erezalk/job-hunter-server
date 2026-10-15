package dev.erezalkalay.jobhunter.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.erezalkalay.jobhunter.jobmatch.ai.ConnectionTestResult;

public record TestConnectionResponse(
        boolean success,
        String message,
        String code,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds
) {

    public static TestConnectionResponse from(ConnectionTestResult result) {
        return new TestConnectionResponse(result.success(), result.message(), result.code(), result.retryAfterSeconds());
    }
}
