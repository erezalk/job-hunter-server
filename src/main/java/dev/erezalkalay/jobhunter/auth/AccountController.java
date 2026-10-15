package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.auth.dto.ChangePasswordRequest;
import dev.erezalkalay.jobhunter.auth.dto.CurrentUserResponse;
import dev.erezalkalay.jobhunter.auth.dto.RegisterRequest;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-up (public, CSRF-protected) and change password (logged-in browser session). */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AccountService accountService;
    private final SessionAuthentication sessionAuthentication;

    public AccountController(AccountService accountService, SessionAuthentication sessionAuthentication) {
        this.accountService = accountService;
        this.sessionAuthentication = sessionAuthentication;
    }

    /** Creates the account and logs it in on this session (201, same body as login). */
    @PostMapping("/register")
    public ResponseEntity<CurrentUserResponse> register(
            @RequestBody RegisterRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // Anonymous requests have no Authentication here; a logged-in session must log out first.
        if (authentication != null && authentication.isAuthenticated()) {
            throw new InvalidRequestException("You are already signed in. Sign out before creating another account.");
        }
        User user = accountService.register(body, request.getRemoteAddr());
        sessionAuthentication.logIn(user, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(CurrentUserResponse.from(user));
    }

    /**
     * 204 on success. This session stays logged in (under a new session id);
     * every other session of the user is ended.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest body,
            Authentication authentication,
            HttpServletRequest request
    ) {
        accountService.changePassword(body);
        sessionAuthentication.revokeOtherSessions(authentication, request);
        return ResponseEntity.noContent().build();
    }
}
