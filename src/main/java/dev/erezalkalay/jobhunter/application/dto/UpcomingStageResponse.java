package dev.erezalkalay.jobhunter.application.dto;

import java.time.Instant;

/**
 * Lean projection for the header's "Upcoming stages" list: just what a row
 * shows (name, start and optional end time, job, company) plus the ids needed to open the ticket. Deliberately omits notes,
 * interviewers, outcome and the job description.
 */
public record UpcomingStageResponse(
        Long stageId,
        String stageName,
        Instant scheduledAt,
        Instant scheduledEndAt,
        Long applicationId,
        Long jobId,
        String jobTitle,
        String companyName
) {
}
