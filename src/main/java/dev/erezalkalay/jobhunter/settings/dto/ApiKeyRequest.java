package dev.erezalkalay.jobhunter.settings.dto;

import jakarta.validation.constraints.NotBlank;

public record ApiKeyRequest(@NotBlank String provider, @NotBlank String apiKey) {
}
