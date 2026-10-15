package dev.erezalkalay.jobhunter.job.dto;

import dev.erezalkalay.jobhunter.job.Job;
import java.math.BigDecimal;
import java.time.Instant;

public record JobResponse(
        Long id,
        Long companyId,
        String companyName,
        String title,
        String description,
        boolean descriptionDiscarded,
        String location,
        String workModel,
        String url,
        Integer salaryMin,
        Integer salaryMax,
        String currency,
        String source,
        BigDecimal fitScore,
        Instant discoveredAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static JobResponse from(Job job) {
        return from(job, null);
    }

    public static JobResponse from(Job job, BigDecimal fitScore) {
        return new JobResponse(
                job.getId(),
                job.getCompany() != null ? job.getCompany().getId() : null,
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getTitle(),
                job.getDescription(),
                job.isDescriptionDiscarded(),
                job.getLocation(),
                job.getWorkModel(),
                job.getUrl(),
                job.getSalaryMin(),
                job.getSalaryMax(),
                job.getCurrency(),
                job.getSource(),
                fitScore,
                job.getDiscoveredAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
