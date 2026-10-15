package dev.erezalkalay.jobhunter.auth.dto;

import dev.erezalkalay.jobhunter.user.User;

/** The logged-in user. Only these fields: never the password hash. {@code fullName} is null when none was given (sign-up asks for none). */
public record CurrentUserResponse(Long id, String email, String fullName) {

    public static CurrentUserResponse from(User user) {
        String fullName = user.getFullName() == null || user.getFullName().isBlank() ? null : user.getFullName();
        return new CurrentUserResponse(user.getId(), user.getEmail(), fullName);
    }
}
