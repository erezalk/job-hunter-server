package dev.erezalkalay.jobhunter.job.dto;

import dev.erezalkalay.jobhunter.job.Job;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        @Size(max = Job.DESCRIPTION_MAX_LENGTH) String description,
        @Size(max = 255) String location,
        @Size(max = 100) String workModel,
        @Size(max = 1000) @Pattern(regexp = "^(https?://\\S+)?$", message = "Enter a valid http(s) URL") String url,
        @PositiveOrZero Integer salaryMin,
        @PositiveOrZero Integer salaryMax,
        @Size(max = 10) String currency,
        // Only honoured when editing a job (PATCH); on create the server assigns it.
        @Size(max = 100) String source
) {
}
