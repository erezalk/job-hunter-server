package dev.erezalkalay.jobhunter.auth.dto;

/** Change password of the logged-in user; validated by AccountService. */
public record ChangePasswordRequest(String currentPassword, String newPassword, String confirmNewPassword) {

    // Never print a password, even by accident (e.g. a log line of the request).
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
