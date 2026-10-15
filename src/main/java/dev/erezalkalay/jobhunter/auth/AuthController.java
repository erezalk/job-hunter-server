package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.auth.dto.CurrentUserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUserProvider currentUserProvider;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(CurrentUserProvider currentUserProvider, CsrfTokenRepository csrfTokenRepository) {
        this.currentUserProvider = currentUserProvider;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        return CurrentUserResponse.from(currentUserProvider.getCurrentUser());
    }

    /**
     * Visiting this once primes the XSRF-TOKEN cookie so the SPA can read it
     * and echo it back as the X-XSRF-TOKEN header on later requests.
     */
    @GetMapping("/csrf")
    public void csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }
}
