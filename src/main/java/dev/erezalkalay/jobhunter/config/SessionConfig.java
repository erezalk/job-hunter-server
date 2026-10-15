package dev.erezalkalay.jobhunter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Session bookkeeping shared by the browser filter chain and the account
 * endpoints (sign-up logs the new user in; change password revokes the
 * user's other sessions).
 *
 * <p>Sessions are ordinary in-memory servlet sessions of this one instance.
 * The registry tracks every authenticated one per user, in the same memory,
 * so it sees all of them. A horizontally scaled deployment would move both
 * to shared storage (e.g. Spring Session, whose SessionRegistry adapter
 * replaces this bean).
 */
@Configuration
public class SessionConfig {

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Forwards servlet session lifecycle events to the registry: destroyed
     * sessions are dropped from it, and a changed session id (login, password
     * change) is followed, so the session stays revocable.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** The browser chain's default repository, as a bean so sign-up can save its login the same way. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }
}
