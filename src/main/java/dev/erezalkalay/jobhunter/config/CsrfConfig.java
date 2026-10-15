package dev.erezalkalay.jobhunter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * Defined separately from {@link SecurityConfig} so the repository bean can
 * be injected into the login success/failure handlers, which SecurityConfig
 * itself depends on; declaring it inside SecurityConfig creates a circular
 * bean-creation dependency.
 */
@Configuration
public class CsrfConfig {

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }
}
