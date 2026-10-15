package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/** Session login and revocation outside the form-login filter, with the same guarantees. */
@Component
public class SessionAuthentication {

    private final UserDetailsService userDetailsService;
    private final SessionRegistry sessionRegistry;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthentication(
            UserDetailsService userDetailsService,
            SessionRegistry sessionRegistry,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository
    ) {
        this.userDetailsService = userDetailsService;
        this.sessionRegistry = sessionRegistry;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        // What the browser chain applies on form login: a new session id
        // (fixation protection) and registration in the session registry.
        this.sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    /**
     * Logs {@code user} in on this request exactly as a successful form login
     * would: same principal type (so the registry sees one user), credentials
     * erased, a fresh session id, the context saved to the session, and a new
     * CSRF token.
     */
    public void logIn(User user, HttpServletRequest request, HttpServletResponse response) {
        UserDetails principal = userDetailsService.loadUserByUsername(user.getEmail());
        if (principal instanceof CredentialsContainer container) {
            container.eraseCredentials();
        }
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        CsrfToken freshToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(freshToken, request, response);
    }

    /**
     * Ends every other session of the authenticated user (their next request
     * answers 401) and moves this session to a new id, so a copied cookie of
     * this session stops working too. The session itself stays logged in.
     */
    public void revokeOtherSessions(Authentication authentication, HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();
        for (SessionInformation session : sessionRegistry.getAllSessions(authentication.getPrincipal(), false)) {
            if (!session.getSessionId().equals(currentId)) {
                session.expireNow();
            }
        }
        if (current != null) {
            request.changeSessionId();
        }
    }
}
