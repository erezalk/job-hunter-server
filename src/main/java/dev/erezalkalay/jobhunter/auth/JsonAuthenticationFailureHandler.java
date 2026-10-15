package dev.erezalkalay.jobhunter.auth;

import tools.jackson.databind.ObjectMapper;
import dev.erezalkalay.jobhunter.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class JsonAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Refused before the account was looked up: the same answer whether it exists or not.
        if (exception instanceof LoginRateLimitedException limited) {
            int retryAfter = limited.getLimit().getRetryAfterSeconds();
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            objectMapper.writeValue(response.getWriter(), ApiError.withCode(429, "Too Many Requests",
                    limited.getMessage(), "RATE_LIMITED", request.getRequestURI(), retryAfter));
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(401, "Unauthorized", "Invalid email or password", request.getRequestURI()));
    }
}
