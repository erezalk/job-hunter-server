package dev.erezalkalay.jobhunter.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountRulesTest {

    @Test
    void passwordPolicy() {
        assertThat(PasswordPolicy.problem(null)).isEqualTo("Password is required");
        assertThat(PasswordPolicy.problem("")).isEqualTo("Password is required");
        assertThat(PasswordPolicy.problem("1234567")).contains("at least 8");
        assertThat(PasswordPolicy.problem("12345678")).isNull();
        assertThat(PasswordPolicy.problem("😀😀😀😀😀😀😀")).as("7 characters, 14 UTF-16 units").contains("at least 8");
        assertThat(PasswordPolicy.problem("a".repeat(72))).isNull();
        assertThat(PasswordPolicy.problem("a".repeat(73))).contains("too long");
        assertThat(PasswordPolicy.problem("ü".repeat(36))).isNull();
        assertThat(PasswordPolicy.problem("ü".repeat(37))).contains("too long");
        assertThat(PasswordPolicy.problem(" \t ".repeat(4))).contains("spaces");
        assertThat(PasswordPolicy.problem("lowercaseonly")).as("no composition rules").isNull();
    }

    @Test
    void emailRules() {
        assertThat(EmailAddresses.normalize("  A.B+c@Example.com \n")).isEqualTo("A.B+c@Example.com");
        assertThat(EmailAddresses.normalize(null)).isNull();
        assertThat(EmailAddresses.problem(null)).isEqualTo("Email is required");
        assertThat(EmailAddresses.problem("")).isEqualTo("Email is required");
        assertThat(EmailAddresses.problem("A.B+c@Example.com")).isNull();
        assertThat(EmailAddresses.problem("user@sub.example.co.uk")).isNull();
        assertThat(EmailAddresses.problem("user@xn--bcher-kva.example")).isNull();
        String local = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
        assertThat((local + "@" + domain).length()).isEqualTo(EmailAddresses.MAX_LENGTH);
        assertThat(EmailAddresses.problem(local + "@" + domain)).isNull();
        assertThat(EmailAddresses.problem(local + "@" + "e" + domain)).contains("at most 254");
        assertThat(EmailAddresses.problem("a".repeat(65) + "@example.com")).isEqualTo("Enter a valid email address");
        assertThat(EmailAddresses.problem("a@b")).isEqualTo("Enter a valid email address");
        assertThat(EmailAddresses.problem("a\u0000b@example.com")).isEqualTo("Enter a valid email address");
    }

    @Test
    void theEncoderIsBcryptAndTreatsOverlongInputAsNoMatch() {
        PasswordEncoder encoder = new BcryptLimitAwarePasswordEncoder();
        String hash = encoder.encode("a".repeat(72));
        assertThat(hash).startsWith("$2a$10$");
        assertThat(encoder.matches("a".repeat(72), hash)).isTrue();
        assertThat(encoder.matches("a".repeat(73), hash)).isFalse();
        assertThat(encoder.matches("a".repeat(10_000), hash)).isFalse();
    }
}
