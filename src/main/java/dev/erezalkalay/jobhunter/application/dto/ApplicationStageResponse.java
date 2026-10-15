package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.ApplicationStage;
import java.time.Instant;

public record ApplicationStageResponse(
        Long id,
        String name,
        Instant scheduledAt,
        String interviewerNames,
        String notes,
        String outcome,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApplicationStageResponse from(ApplicationStage stage) {
        return new ApplicationStageResponse(
                stage.getId(),
                stage.getName(),
                stage.getScheduledAt(),
                stage.getInterviewerNames(),
                stage.getNotes(),
                stage.getOutcome(),
                stage.getCreatedAt(),
                stage.getUpdatedAt()
        );
    }
}
