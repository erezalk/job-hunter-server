package dev.erezalkalay.jobhunter.application.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusChangeRequest(@NotBlank String statusCode, String notes) {
}
