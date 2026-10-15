package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.auth.ratelimit.AuthRateLimiter;
import dev.erezalkalay.jobhunter.auth.ratelimit.RateLimitExceededException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Applies the login rate limit inside authentication itself, so it covers
 * every way a password can be submitted to the form login (no URL variant
 * can skip it), and counts the attempt before the account is looked up.
 * It is the only provider of an AuthenticationManager without a parent (see
 * SecurityConfig): a refused attempt can never fall through to another
 * provider that would check the password anyway.
 */
public class RateLimitedAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider delegate;
    private final AuthRateLimiter rateLimiter;

    public RateLimitedAuthenticationProvider(AuthenticationProvider delegate, AuthRateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String clientIp = authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress()
                : "unknown";
        try {
            rateLimiter.checkLogin(clientIp, authentication.getName());
        } catch (RateLimitExceededException limited) {
            throw new LoginRateLimitedException(limited);
        }
        // No account can have an email this long: refuse without a database lookup.
        if (authentication.getName().length() > EmailAddresses.MAX_LENGTH) {
            throw new BadCredentialsException("Bad credentials");
        }
        return delegate.authenticate(authentication);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
