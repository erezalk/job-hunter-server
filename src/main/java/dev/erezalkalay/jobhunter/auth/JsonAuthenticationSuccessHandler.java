package dev.erezalkalay.jobhunter.auth;

import tools.jackson.databind.ObjectMapper;
import dev.erezalkalay.jobhunter.auth.dto.CurrentUserResponse;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public class JsonAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final CsrfTokenRepository csrfTokenRepository;

    public JsonAuthenticationSuccessHandler(
            UserRepository userRepository, ObjectMapper objectMapper, CsrfTokenRepository csrfTokenRepository) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        // Spring Security's CsrfAuthenticationStrategy deletes the pre-login
        // CSRF cookie on successful auth (fixation protection). Reissue one
        // immediately so the SPA can make its next mutating request without
        // an extra round trip to re-prime it.
        CsrfToken freshToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(freshToken, request, response);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), CurrentUserResponse.from(user));
    }
}
