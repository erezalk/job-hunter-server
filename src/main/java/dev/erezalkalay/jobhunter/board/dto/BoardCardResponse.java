package dev.erezalkalay.jobhunter.board.dto;

import dev.erezalkalay.jobhunter.application.Application;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lean projection for the Board: one card per Application. Deliberately
 * omits the job description and other large/detail-only fields, which are
 * loaded on demand by the ticket drawer via GET /api/jobs/{id}.
 */
public record BoardCardResponse(
        Long applicationId,
        Long jobId,
        String companyName,
        String title,
        String location,
        String workModel,
        BigDecimal fitScore,
        String statusCode,
        boolean starred,
        Instant discoveredAt,
        Instant createdAt
) {

    public static BoardCardResponse from(Application application, BigDecimal fitScore) {
        var job = application.getJob();
        return new BoardCardResponse(
                application.getId(),
                job.getId(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getTitle(),
                job.getLocation(),
                job.getWorkModel(),
                fitScore,
                application.getStatus().getCode(),
                application.isStarred(),
                job.getDiscoveredAt(),
                application.getCreatedAt()
        );
    }
}
