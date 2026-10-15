package dev.erezalkalay.jobhunter.auth;

import dev.erezalkalay.jobhunter.auth.dto.ChangePasswordRequest;
import dev.erezalkalay.jobhunter.auth.dto.RegisterRequest;
import dev.erezalkalay.jobhunter.auth.ratelimit.AuthRateLimiter;
import dev.erezalkalay.jobhunter.common.exception.FieldValidationException;
import dev.erezalkalay.jobhunter.settings.Settings;
import dev.erezalkalay.jobhunter.settings.SettingsRepository;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up and change password. Passwords are only ever passed to the
 * PasswordEncoder: never stored, logged, returned or put in an exception
 * message.
 */
@Service
public class AccountService {

    private static final String CONFIRM_MISMATCH = "Passwords do not match";

    private final UserRepository userRepository;
    private final SettingsRepository settingsRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final TransactionTemplate transactionTemplate;

    public AccountService(
            UserRepository userRepository,
            SettingsRepository settingsRepository,
            CurrentUserProvider currentUserProvider,
            PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.currentUserProvider = currentUserProvider;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates an account with default settings and nothing else (no CV, AI
     * key, MCP token or jobs). The email is stored trimmed, case as entered;
     * an existing account with the same email ignoring case is a 409. The
     * unique index on upper(email) decides concurrent sign-ups: exactly one
     * wins, the others get the same 409.
     */
    public User register(RegisterRequest request, String clientIp) {
        String email = EmailAddresses.normalize(request.email());
        Map<String, String> errors = new LinkedHashMap<>();
        putIfPresent(errors, "email", EmailAddresses.problem(email));
        putIfPresent(errors, "password", PasswordPolicy.problem(request.password()));
        putIfPresent(errors, "confirmPassword", confirmationProblem(request.password(), request.confirmPassword()));
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }

        // Counted only for well-formed requests: those are the ones that create
        // accounts or reveal that one exists.
        rateLimiter.checkSignup(clientIp);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        // BCrypt is deliberately slow: hash before the transaction opens.
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            return transactionTemplate.execute(status -> {
                // No name is asked for at sign-up; full_name is NOT NULL, so it is stored empty.
                User user = userRepository.saveAndFlush(new User(email, passwordHash, ""));
                settingsRepository.save(new Settings(user));
                return user;
            });
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateEmail(e)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }
    }

    /**
     * Replaces the current user's password after verifying the current one.
     * Touches only {@code users.password_hash} (and its updated_at): jobs, CV,
     * settings, API keys and the MCP token are unchanged. Ending the user's
     * other sessions is the caller's job, after this commits.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.currentPassword() == null || request.currentPassword().isEmpty()) {
            errors.put("currentPassword", "Current password is required");
        }
        putIfPresent(errors, "newPassword", PasswordPolicy.problem(request.newPassword()));
        putIfPresent(errors, "confirmNewPassword", confirmationProblem(request.newPassword(), request.confirmNewPassword()));
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }

        User user = currentUserProvider.getCurrentUser();
        // A stolen session must not be able to guess the current password at speed.
        rateLimiter.checkPasswordChange(user.getId());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new FieldValidationException("currentPassword", "Current password is incorrect");
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new FieldValidationException("newPassword", "New password must be different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    private static String confirmationProblem(String password, String confirmation) {
        if (confirmation == null || confirmation.isEmpty()) {
            return "Confirm the password";
        }
        return confirmation.equals(password) ? null : CONFIRM_MISMATCH;
    }

    private static void putIfPresent(Map<String, String> errors, String field, String problem) {
        if (problem != null) {
            errors.put(field, problem);
        }
    }

    // uq_users_email (exact) or uq_users_email_ignore_case (upper(email)).
    private static boolean isDuplicateEmail(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT).contains("uq_users_email")) {
                return true;
            }
        }
        return false;
    }
}
