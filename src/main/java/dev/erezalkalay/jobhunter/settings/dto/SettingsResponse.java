package dev.erezalkalay.jobhunter.settings.dto;

import dev.erezalkalay.jobhunter.settings.Settings;
import java.util.List;
import org.springframework.util.StringUtils;

public record SettingsResponse(
        String theme,
        String timezone,
        List<String> visibleBoardStatuses,
        int minimumFitScore,
        String aiProvider,
        String aiModel,
        boolean anthropicApiKeyConfigured,
        boolean geminiApiKeyConfigured
) {

    public static SettingsResponse from(Settings settings) {
        return new SettingsResponse(
                settings.getTheme(),
                settings.getTimezone(),
                settings.getVisibleBoardStatusCodes(),
                settings.getMinimumFitScore(),
                settings.getAiProvider(),
                settings.getAiModel(),
                StringUtils.hasText(settings.getAnthropicApiKeyEncrypted()),
                StringUtils.hasText(settings.getGeminiApiKeyEncrypted())
        );
    }
}
