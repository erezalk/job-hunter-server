package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.job.dto.JobRequest;
import dev.erezalkalay.jobhunter.jobmatch.ImportedMatch;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Body of POST /api/integrations/jobs: the Add Job fields plus an optional,
 * already-calculated job-fit result from the external AI. There is no owner
 * and no source field — the owner is the token's user and the source is
 * always "External AI"; unknown JSON properties are ignored.
 */
public record IntegrationAddJobRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        @Size(max = Job.DESCRIPTION_MAX_LENGTH) String description,
        @Size(max = 255) String location,
        @Size(max = 100) String workModel,
        @Size(max = 1000) @Pattern(regexp = "^(https?://\\S+)?$", message = "Enter a valid http(s) URL") String url,
        @PositiveOrZero Integer salaryMin,
        @PositiveOrZero Integer salaryMax,
        @Size(max = 10) String currency,
        // BigDecimal so a fractional score is rejected instead of being silently truncated to an int.
        @DecimalMin("0") @DecimalMax("100") BigDecimal fitScore,
        @Size(max = 50) List<@Size(max = 1000) String> matchingSkills,
        @Size(max = 50) List<@Size(max = 1000) String> missingSkills,
        @Size(max = 50) List<@Size(max = 1000) String> concerns,
        @Size(max = 100) String evaluationModel
) {

    public JobRequest toJobRequest() {
        return new JobRequest(title, companyName, description, location, workModel, url, salaryMin, salaryMax, currency, null);
    }

    /** Null when no evaluation was supplied; throws InvalidRequestException for inconsistent evaluation data. */
    public ImportedMatch toImportedMatch() {
        return ImportedMatch.of(fitScore, matchingSkills, missingSkills, concerns, evaluationModel);
    }
}
