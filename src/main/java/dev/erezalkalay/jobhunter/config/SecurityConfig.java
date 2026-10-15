package dev.erezalkalay.jobhunter.config;

import dev.erezalkalay.jobhunter.auth.BcryptLimitAwarePasswordEncoder;
import dev.erezalkalay.jobhunter.auth.JsonAuthenticationFailureHandler;
import dev.erezalkalay.jobhunter.auth.JsonAuthenticationSuccessHandler;
import dev.erezalkalay.jobhunter.auth.RateLimitedAuthenticationProvider;
import dev.erezalkalay.jobhunter.auth.ratelimit.AuthRateLimiter;
import dev.erezalkalay.jobhunter.common.exception.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    private final JsonAuthenticationSuccessHandler successHandler;
    private final JsonAuthenticationFailureHandler failureHandler;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SessionRegistry sessionRegistry;
    private final SecurityContextRepository securityContextRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(
            JsonAuthenticationSuccessHandler successHandler,
            JsonAuthenticationFailureHandler failureHandler,
            CsrfTokenRepository csrfTokenRepository,
            SessionRegistry sessionRegistry,
            SecurityContextRepository securityContextRepository,
            ObjectMapper objectMapper
    ) {
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.csrfTokenRepository = csrfTokenRepository;
        this.sessionRegistry = sessionRegistry;
        this.securityContextRepository = securityContextRepository;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, UserDetailsService userDetailsService, AuthRateLimiter rateLimiter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> configureCsrf(csrf, csrfTokenRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                        // Sign-up only; still CSRF-protected like every POST of this chain.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationManager(loginAuthenticationManager(userDetailsService, rateLimiter))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // Unlimited sessions per user, but every authenticated session is
                // tracked so a password change can end the user's other sessions;
                // an ended session answers 401 like any lost session.
                .sessionManagement(session -> session.sessionConcurrency(concurrency -> concurrency
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> sessionEnded(event.getResponse(), event.getRequest().getRequestURI()))))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                );
        return http.build();
    }

    /**
     * The login's only AuthenticationManager: the rate limit, then the normal
     * user-details/BCrypt check. Deliberately without a parent manager, so a
     * rate-limited attempt cannot be retried by another provider.
     */
    private AuthenticationManager loginAuthenticationManager(UserDetailsService userDetailsService, AuthRateLimiter rateLimiter) {
        DaoAuthenticationProvider passwordCheck = new DaoAuthenticationProvider(userDetailsService);
        passwordCheck.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(new RateLimitedAuthenticationProvider(passwordCheck, rateLimiter));
    }

    private void sessionEnded(HttpServletResponse response, String path) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(401, "Unauthorized", "Your session has ended. Please sign in again.", path));
    }

    private void configureCsrf(CsrfConfigurer<HttpSecurity> csrf, CsrfTokenRepository repository) {
        // The plain (non-BREACH-masking) request handler is required here: the
        // SPA reads the raw XSRF-TOKEN cookie value in JS and echoes it back
        // verbatim as the X-XSRF-TOKEN header, so the server must compare it
        // unmasked. The default XorCsrfTokenRequestAttributeHandler expects a
        // masked value and would reject every request.
        csrf.csrfTokenRepository(repository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptLimitAwarePasswordEncoder();
    }
}
