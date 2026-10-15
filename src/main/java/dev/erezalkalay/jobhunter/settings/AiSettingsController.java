package dev.erezalkalay.jobhunter.settings;

import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiJobMatchProvider;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiJobMatchProviderRegistry;
import dev.erezalkalay.jobhunter.jobmatch.ai.ConnectionTestResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.ProviderId;
import dev.erezalkalay.jobhunter.settings.dto.ApiKeyRequest;
import dev.erezalkalay.jobhunter.settings.dto.SettingsResponse;
import dev.erezalkalay.jobhunter.settings.dto.TestConnectionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider API-key mutation and the "Test connection" action — kept separate
 * from the general {@link SettingsController} so secret handling has its own
 * small, obviously-safe surface (never returns a key, never accepts one
 * bundled into the same payload as unrelated preferences).
 */
@RestController
@RequestMapping("/api/settings/ai")
public class AiSettingsController {

    private final SettingsService settingsService;
    private final AiJobMatchProviderRegistry providerRegistry;

    public AiSettingsController(SettingsService settingsService, AiJobMatchProviderRegistry providerRegistry) {
        this.settingsService = settingsService;
        this.providerRegistry = providerRegistry;
    }

    @PutMapping("/api-key")
    public SettingsResponse setApiKey(@Valid @RequestBody ApiKeyRequest request) {
        settingsService.setApiKey(parseProvider(request.provider()), request.apiKey());
        return SettingsResponse.from(settingsService.getForCurrentUser());
    }

    @DeleteMapping("/api-key")
    public SettingsResponse removeApiKey(@RequestParam String provider) {
        settingsService.removeApiKey(parseProvider(provider));
        return SettingsResponse.from(settingsService.getForCurrentUser());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAiConfiguration() {
        settingsService.removeAiConfiguration();
    }

    @PostMapping("/test-connection")
    public TestConnectionResponse testConnection() {
        Settings settings = settingsService.getForCurrentUser();
        if (!StringUtils.hasText(settings.getAiProvider()) || !StringUtils.hasText(settings.getAiModel())) {
            throw new InvalidRequestException("Configure an AI provider and model before testing the connection");
        }
        ProviderId providerId = parseProvider(settings.getAiProvider());
        String apiKey = settingsService.getDecryptedApiKey(providerId);
        if (!StringUtils.hasText(apiKey)) {
            throw new InvalidRequestException("Configure an API key before testing the connection");
        }

        AiJobMatchProvider provider = providerRegistry.get(providerId);
        ConnectionTestResult result = provider.testConnection(apiKey, settings.getAiModel());
        return TestConnectionResponse.from(result);
    }

    private static ProviderId parseProvider(String value) {
        if (!ProviderId.isValid(value)) {
            throw new InvalidRequestException("Unsupported AI provider: " + value);
        }
        return ProviderId.valueOf(value);
    }
}
