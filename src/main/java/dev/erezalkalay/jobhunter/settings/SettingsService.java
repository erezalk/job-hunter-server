package dev.erezalkalay.jobhunter.settings;

import dev.erezalkalay.jobhunter.application.ApplicationStatus;
import dev.erezalkalay.jobhunter.application.ApplicationStatusRepository;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.crypto.EncryptionService;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.jobmatch.ai.ProviderId;
import dev.erezalkalay.jobhunter.settings.dto.SettingsRequest;
import dev.erezalkalay.jobhunter.user.User;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class SettingsService {

    private final SettingsRepository settingsRepository;
    private final CurrentUserProvider currentUserProvider;
    private final EncryptionService encryptionService;
    private final JobMatchRepository jobMatchRepository;
    private final ApplicationStatusRepository applicationStatusRepository;

    public SettingsService(
            SettingsRepository settingsRepository,
            CurrentUserProvider currentUserProvider,
            EncryptionService encryptionService,
            JobMatchRepository jobMatchRepository,
            ApplicationStatusRepository applicationStatusRepository
    ) {
        this.settingsRepository = settingsRepository;
        this.currentUserProvider = currentUserProvider;
        this.encryptionService = encryptionService;
        this.jobMatchRepository = jobMatchRepository;
        this.applicationStatusRepository = applicationStatusRepository;
    }

    @Transactional
    public Settings getForCurrentUser() {
        User user = currentUserProvider.getCurrentUser();
        return settingsRepository.findByUserId(user.getId())
                .orElseGet(() -> settingsRepository.save(new Settings(user)));
    }

    @Transactional
    public Settings updateForCurrentUser(SettingsRequest request) {
        // Belt-and-braces alongside the DTO's @NotEmpty: at least one Board
        // column must stay visible, defended here too in case this method is
        // ever called from somewhere that skips bean validation.
        if (request.visibleBoardStatuses() == null || request.visibleBoardStatuses().isEmpty()) {
            throw new InvalidRequestException("At least one Board column must remain visible");
        }
        // Only real status codes: an unknown value (or one containing a comma,
        // which would corrupt the comma-separated column) is rejected, never dropped.
        Set<String> knownCodes = applicationStatusRepository.findAll().stream()
                .map(ApplicationStatus::getCode)
                .collect(Collectors.toSet());
        List<String> unknownCodes = request.visibleBoardStatuses().stream()
                .filter(code -> !knownCodes.contains(code))
                .toList();
        if (!unknownCodes.isEmpty()) {
            throw new InvalidRequestException("Unknown Board column status: " + String.join(", ", unknownCodes));
        }
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw new InvalidRequestException("Unknown timezone: " + request.timezone()
                    + " (use an IANA name such as Europe/Berlin)");
        }
        String provider = normalizeBlank(request.aiProvider());
        String model = normalizeBlank(request.aiModel());
        if (provider != null && !ProviderId.isValid(provider)) {
            throw new InvalidRequestException("Unsupported AI provider: " + provider);
        }
        if (provider != null && model == null) {
            throw new InvalidRequestException("A model must be configured when an AI provider is selected");
        }

        Settings settings = getForCurrentUser();
        settings.setTheme(request.theme());
        settings.setTimezone(request.timezone());
        settings.setVisibleBoardStatusCodes(request.visibleBoardStatuses());
        settings.setMinimumFitScore(request.minimumFitScore());
        settings.setAiProvider(provider);
        settings.setAiModel(model);
        return settings;
    }

    @Transactional
    public void setApiKey(ProviderId provider, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new InvalidRequestException("API key must not be blank");
        }
        Settings settings = getForCurrentUser();
        applyApiKey(settings, provider, encryptionService.encrypt(apiKey));
    }

    @Transactional
    public void removeApiKey(ProviderId provider) {
        Settings settings = getForCurrentUser();
        applyApiKey(settings, provider, null);
    }

    /**
     * Returns the current user to the "never configured AI Matching" state:
     * both providers' encrypted keys, provider, model, the minimum fit score
     * and every JobMatch the user owns. Idempotent. Touches nothing else
     * (CV, Jobs, Applications, Board/appearance settings).
     */
    @Transactional
    public void removeAiConfiguration() {
        Settings settings = getForCurrentUser();
        settings.setAnthropicApiKeyEncrypted(null);
        settings.setGeminiApiKeyEncrypted(null);
        settings.setAiProvider(null);
        settings.setAiModel(null);
        settings.setMinimumFitScore(Settings.DEFAULT_MINIMUM_FIT_SCORE);
        jobMatchRepository.deleteAllByUserId(settings.getUser().getId());
    }

    /**
     * Decrypted key for internal use only (making a provider call) — never
     * exposed through any controller/response DTO.
     */
    public String getDecryptedApiKey(ProviderId provider) {
        Settings settings = getForCurrentUser();
        String encrypted = provider == ProviderId.ANTHROPIC
                ? settings.getAnthropicApiKeyEncrypted()
                : settings.getGeminiApiKeyEncrypted();
        return StringUtils.hasText(encrypted) ? encryptionService.decrypt(encrypted) : null;
    }

    private void applyApiKey(Settings settings, ProviderId provider, String encryptedOrNull) {
        if (provider == ProviderId.ANTHROPIC) {
            settings.setAnthropicApiKeyEncrypted(encryptedOrNull);
        } else {
            settings.setGeminiApiKeyEncrypted(encryptedOrNull);
        }
    }

    private static String normalizeBlank(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
