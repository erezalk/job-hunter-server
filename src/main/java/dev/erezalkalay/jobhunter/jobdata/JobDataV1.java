package dev.erezalkalay.jobhunter.jobdata;

import dev.erezalkalay.jobhunter.application.Application;
import dev.erezalkalay.jobhunter.job.Job;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Job/Board data export, format version 1: a logical, self-contained tree of
 * one user's jobs. It deliberately contains no database ids and no user
 * identity — relationships are expressed by nesting (a job holds its
 * application, the application its stages and status history), and each job
 * carries a file-local {@code ref}. The same records are the export output
 * and the (strictly validated) import input. Size limits mirror the current
 * columns and request limits, so a valid file can always be restored.
 */
public record JobDataV1(
        @NotNull String format,
        @NotNull Integer formatVersion,
        @NotNull Instant exportedAt,
        @NotNull @Size(max = MAX_JOBS) List<@NotNull @Valid ExportedJob> jobs
) {

    public static final String FORMAT = "job-hunter-jobs";
    public static final int VERSION = 1;

    static final int MAX_JOBS = 10_000;
    static final int MAX_STAGES = 500;
    static final int MAX_HISTORY = 2_000;
    static final int MAX_LIST_ITEMS = 500;
    static final int NOTES = Application.NOTES_MAX_LENGTH;

    /** A job and the current user's tracking of it. {@code ref} is unique within the file only. */
    public record ExportedJob(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String ref,
            @NotBlank @Size(max = 255) String title,
            @Valid ExportedCompany company,
            @Size(max = Job.DESCRIPTION_MAX_LENGTH) String description,
            @NotNull Boolean descriptionDiscarded,
            @Size(max = 255) String location,
            @Size(max = 100) String workModel,
            @Size(max = 1000) String url,
            @PositiveOrZero Integer salaryMin,
            @PositiveOrZero Integer salaryMax,
            @Size(max = 10) String currency,
            @Size(max = Job.SOURCE_MAX_LENGTH) String source,
            @Size(max = 255) String externalId,
            @NotNull Instant discoveredAt,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt,
            @NotNull @Valid ExportedApplication application,
            @Valid ExportedAiMatch aiMatch
    ) {
    }

    public record ExportedCompany(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 500) String website,
            @Size(max = NOTES) String notes
    ) {
    }

    /** The Board ticket: status, order, star, notes and its full history. */
    public record ExportedApplication(
            @NotBlank @Size(max = 50) String status,
            @NotNull Integer boardPosition,
            @NotNull Boolean starred,
            @Size(max = NOTES) String notes,
            Instant appliedAt,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt,
            @NotNull @Size(max = MAX_HISTORY) List<@NotNull @Valid ExportedStatusChange> statusHistory,
            @NotNull @Size(max = MAX_STAGES) List<@NotNull @Valid ExportedStage> stages
    ) {
    }

    public record ExportedStatusChange(
            @NotBlank @Size(max = 50) String status,
            @NotNull Instant enteredAt,
            @Size(max = NOTES) String notes
    ) {
    }

    public record ExportedStage(
            @NotBlank @Size(max = 255) String name,
            Instant scheduledAt,
            @Size(max = 500) String interviewerNames,
            @Size(max = NOTES) String notes,
            @Size(max = 255) String outcome,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt
    ) {
    }

    /**
     * The user's AI evaluation of the job. No CV reference and no reuse
     * fingerprint: both identify rows of this database (the CV is not
     * exported), so a restored match is shown but never reused as a cache hit.
     */
    public record ExportedAiMatch(
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal fitScore,
            @NotNull @Size(max = MAX_LIST_ITEMS) List<@NotNull @Size(max = 1000) String> matchingSkills,
            @NotNull @Size(max = MAX_LIST_ITEMS) List<@NotNull @Size(max = 1000) String> missingSkills,
            @NotNull @Size(max = MAX_LIST_ITEMS) List<@NotNull @Size(max = 1000) String> concerns,
            @NotBlank @Size(max = 30) String provider,
            @Size(max = 100) String model,
            Instant evaluatedAt,
            @NotNull Instant createdAt
    ) {
    }
}
