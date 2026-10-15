package dev.erezalkalay.jobhunter.integration;

import jakarta.servlet.Filter;

/** Public handle on the package-private bearer filter for the security configuration. */
public final class IntegrationSecurity {

    public static final String ADD_JOB_AUTHORITY = BearerTokenFilter.ADD_JOB_AUTHORITY;

    private IntegrationSecurity() {
    }

    public static Filter bearerFilter(IntegrationTokenService tokenService) {
        return new BearerTokenFilter(tokenService);
    }
}
