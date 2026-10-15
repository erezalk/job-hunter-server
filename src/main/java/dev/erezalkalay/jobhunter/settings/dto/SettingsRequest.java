package dev.erezalkalay.jobhunter.settings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SettingsRequest(
        // Sizes mirror the settings columns so an overlong value is a 400 field error, not a database failure.
        // The timezone id and the Board status codes are checked in SettingsService.
        @NotBlank @Pattern(regexp = "SYSTEM|LIGHT|DARK", message = "must be one of SYSTEM, LIGHT, DARK") String theme,
        @NotBlank @Size(max = 100) String timezone,
        @NotEmpty List<@NotBlank String> visibleBoardStatuses,
        @NotNull @Min(0) @Max(100) Integer minimumFitScore,
        @Size(max = 20) String aiProvider,
        @Size(max = 100) String aiModel
) {
}
