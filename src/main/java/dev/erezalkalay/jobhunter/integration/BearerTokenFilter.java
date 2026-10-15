package dev.erezalkalay.jobhunter.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates "Authorization: Bearer jh_..." on the integration filter chain
 * only. Deliberately NOT a Spring bean (a Filter bean would be auto-registered
 * for every request). The resulting authentication carries a single narrow
 * authority; nothing in the browser session chain recognises it.
 */
class BearerTokenFilter extends OncePerRequestFilter {

    static final String ADD_JOB_AUTHORITY = "SCOPE_jobs:add";

    private static final String BEARER = "Bearer ";

    private final IntegrationTokenService tokenService;

    BearerTokenFilter(IntegrationTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            tokenService.resolveOwner(header.substring(BEARER.length()).trim()).ifPresent(owner ->
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            owner.getEmail(), null, List.of(new SimpleGrantedAuthority(ADD_JOB_AUTHORITY)))));
        }
        chain.doFilter(request, response);
    }
}
