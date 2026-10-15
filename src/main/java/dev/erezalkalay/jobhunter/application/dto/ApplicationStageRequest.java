package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.Application;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ApplicationStageRequest(
        @NotBlank @Size(max = 255) String name,
        Instant scheduledAt,
        @Size(max = 500) String interviewerNames,
        @Size(max = Application.NOTES_MAX_LENGTH) String notes,
        @Size(max = 255) String outcome
) {
}
