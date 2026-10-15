package dev.erezalkalay.jobhunter.settings;

import dev.erezalkalay.jobhunter.settings.dto.SettingsRequest;
import dev.erezalkalay.jobhunter.settings.dto.SettingsResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsResponse getSettings() {
        return SettingsResponse.from(settingsService.getForCurrentUser());
    }

    @PutMapping
    public SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest request) {
        return SettingsResponse.from(settingsService.updateForCurrentUser(request));
    }
}
