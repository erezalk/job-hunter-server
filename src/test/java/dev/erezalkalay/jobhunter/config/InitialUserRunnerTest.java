package dev.erezalkalay.jobhunter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.erezalkalay.jobhunter.settings.Settings;
import dev.erezalkalay.jobhunter.settings.SettingsRepository;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The bootstrap decision only; repositories are in-memory fakes, no database. */
@ExtendWith(OutputCaptureExtension.class)
class InitialUserRunnerTest {

    private static final String PASSWORD = "bootstrap-pass-123";

    private long existingUsers;
    private final List<Object> saved = new ArrayList<>();

    @Test
    void createsTheUserWhenBothEmailAndPasswordAreSet(CapturedOutput output) {
        runner(" Owner@Example.com ", PASSWORD, "Owner").run(null);

        assertThat(saved).hasSize(2);
        User user = (User) saved.get(0);
        assertThat(user.getEmail()).as("trimmed, case kept, like sign-up").isEqualTo("Owner@Example.com");
        assertThat(user.getFullName()).isEqualTo("Owner");
        assertThat(user.getPasswordHash()).isEqualTo("hashed:" + PASSWORD.length());
        assertThat(((Settings) saved.get(1)).getUser()).isSameAs(user);
        assertThat(output).contains("Created initial user 'Owner@Example.com'").doesNotContain(PASSWORD);
    }

    @Test
    void createsNothingWhenNeitherIsSet(CapturedOutput output) {
        runner("", " ", "Job Hunter").run(null);

        assertThat(saved).isEmpty();
        assertThat(output).contains("create the first account through Sign up").doesNotContain("admin@jobhunter.local");
    }

    @Test
    void neverGeneratesAPasswordWhenOnlyTheEmailIsSet() {
        assertThatThrownBy(() -> runner("owner@example.com", "", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be set");
        assertThat(saved).isEmpty();
    }

    @Test
    void refusesWhenOnlyThePasswordIsSet() {
        assertThatThrownBy(() -> runner(null, PASSWORD, "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be set")
                .hasMessageNotContaining(PASSWORD);
        assertThat(saved).isEmpty();
    }

    @Test
    void appliesTheSignUpRulesWithoutRevealingThePassword() {
        assertThatThrownBy(() -> runner("not-an-email", PASSWORD, "").run(null))
                .hasMessageContaining("APP_INITIAL_USER_EMAIL is not usable");
        assertThatThrownBy(() -> runner("owner@example.com", "short", "").run(null))
                .hasMessageContaining("APP_INITIAL_USER_PASSWORD is not usable: Password must be at least 8")
                .hasMessageNotContaining("short");
        assertThat(saved).isEmpty();
    }

    @Test
    void ignoresTheSettingsOnceAnyUserExists() {
        existingUsers = 1;

        runner("owner@example.com", PASSWORD, "").run(null);
        runner("owner@example.com", "", "").run(null);
        runner("", "", "").run(null);

        assertThat(saved).isEmpty();
    }

    private InitialUserRunner runner(String email, String password, String fullName) {
        return new InitialUserRunner(fake(UserRepository.class), fake(SettingsRepository.class), new FakeEncoder(),
                email, password, fullName);
    }

    private <T> T fake(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "count" -> existingUsers;
                    case "save" -> {
                        saved.add(args[0]);
                        yield args[0];
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    /** Records only the length, so the test never holds a reversible "hash". */
    private static final class FakeEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return "hashed:" + rawPassword.length();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }
    }
}
