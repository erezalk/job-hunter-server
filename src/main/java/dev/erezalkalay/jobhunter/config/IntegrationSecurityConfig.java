package dev.erezalkalay.jobhunter.config;

import dev.erezalkalay.jobhunter.integration.IntegrationTokenService;
import dev.erezalkalay.jobhunter.integration.IntegrationSecurity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Separate, stateless filter chain for /api/integrations/** (personal MCP
 * token). CSRF is disabled ONLY here: this chain ignores cookies/sessions
 * entirely and authenticates solely from the Authorization header, so there
 * is no ambient browser credential for a cross-site request to ride on. The
 * browser chain (SecurityConfig) is untouched and keeps session + CSRF.
 */
@Configuration
public class IntegrationSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain integrationSecurityFilterChain(HttpSecurity http, IntegrationTokenService tokenService)
            throws Exception {
        http
                .securityMatcher("/api/integrations/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(IntegrationSecurity.ADD_JOB_AUTHORITY))
                .addFilterBefore(IntegrationSecurity.bearerFilter(tokenService), AuthorizationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> unauthorized(res))
                        .accessDeniedHandler((req, res, e) -> unauthorized(res)));
        return http.build();
    }

    private static void unauthorized(HttpServletResponse res) throws java.io.IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":401,\"message\":\"Invalid, missing, replaced or revoked integration token\"}");
    }
}
