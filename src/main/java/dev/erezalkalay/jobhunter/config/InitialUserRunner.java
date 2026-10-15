package dev.erezalkalay.jobhunter.config;

import dev.erezalkalay.jobhunter.auth.EmailAddresses;
import dev.erezalkalay.jobhunter.auth.PasswordPolicy;
import dev.erezalkalay.jobhunter.settings.Settings;
import dev.erezalkalay.jobhunter.settings.SettingsRepository;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optional bootstrap account, opt-in: on a start with an empty {@code users}
 * table, creates one user only when APP_INITIAL_USER_EMAIL and
 * APP_INITIAL_USER_PASSWORD are both set. Otherwise nothing is created and
 * accounts come from Sign up. A password is never generated or logged. Once
 * any user exists the settings are ignored, so leaving them set is harmless.
 */
@Component
public class InitialUserRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialUserRunner.class);

    private final UserRepository userRepository;
    private final SettingsRepository settingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final String configuredEmail;
    private final String configuredPassword;
    private final String configuredFullName;

    public InitialUserRunner(
            UserRepository userRepository,
            SettingsRepository settingsRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.initial-user.email:}") String configuredEmail,
            @Value("${app.initial-user.password:}") String configuredPassword,
            @Value("${app.initial-user.full-name:}") String configuredFullName
    ) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredEmail = configuredEmail;
        this.configuredPassword = configuredPassword;
        this.configuredFullName = configuredFullName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        boolean emailSet = StringUtils.hasText(configuredEmail);
        boolean passwordSet = StringUtils.hasText(configuredPassword);
        if (!emailSet && !passwordSet) {
            log.info("No users yet and no initial user configured: create the first account through Sign up");
            return;
        }
        // The operator asked for an account but it cannot be created as configured:
        // stop rather than start without it. Messages never contain the password.
        if (!emailSet || !passwordSet) {
            throw new IllegalStateException("APP_INITIAL_USER_EMAIL and APP_INITIAL_USER_PASSWORD must both be set "
                    + "to create the initial user (or neither, to use Sign up only)");
        }
        String email = EmailAddresses.normalize(configuredEmail);
        String emailProblem = EmailAddresses.problem(email);
        if (emailProblem != null) {
            throw new IllegalStateException("APP_INITIAL_USER_EMAIL is not usable: " + emailProblem);
        }
        String passwordProblem = PasswordPolicy.problem(configuredPassword);
        if (passwordProblem != null) {
            throw new IllegalStateException("APP_INITIAL_USER_PASSWORD is not usable: " + passwordProblem);
        }

        String fullName = StringUtils.hasText(configuredFullName) ? configuredFullName.strip() : "Job Hunter";
        User user = new User(email, passwordEncoder.encode(configuredPassword), fullName);
        userRepository.save(user);
        settingsRepository.save(new Settings(user));
        log.info("Created initial user '{}' from APP_INITIAL_USER_EMAIL/APP_INITIAL_USER_PASSWORD", email);
    }
}
