package dev.erezalkalay.jobhunter.auth.dto;

/**
 * Sign-up. Only these three fields are read (anything else in the body is
 * ignored); they are validated together by AccountService so every problem is
 * reported at once and the email is checked after trimming.
 */
public record RegisterRequest(String email, String password, String confirmPassword) {

    // Never print a password, even by accident (e.g. a log line of the request).
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + "]";
    }
}
