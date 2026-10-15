package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.Application;
import java.time.Instant;

public record ApplicationResponse(
        Long id,
        Long jobId,
        String jobTitle,
        Long userId,
        ApplicationStatusResponse status,
        Instant appliedAt,
        String notes,
        boolean starred,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApplicationResponse from(Application application) {
        return new ApplicationResponse(
                application.getId(),
                application.getJob().getId(),
                application.getJob().getTitle(),
                application.getUser().getId(),
                ApplicationStatusResponse.from(application.getStatus()),
                application.getAppliedAt(),
                application.getNotes(),
                application.isStarred(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }
}
