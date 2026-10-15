package dev.erezalkalay.jobhunter.jobmatch.ai;

public record ConnectionTestResult(boolean success, String message, String code, Integer retryAfterSeconds) {

    public static ConnectionTestResult ok() {
        return new ConnectionTestResult(true, "Connection successful.", null, null);
    }

    public static ConnectionTestResult failure(AiProviderException e) {
        return new ConnectionTestResult(false, e.getMessage(), e.getCode().name(), e.getRetryAfterSeconds());
    }
}
