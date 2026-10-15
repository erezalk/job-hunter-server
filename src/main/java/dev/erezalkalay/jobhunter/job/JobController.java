package dev.erezalkalay.jobhunter.job;

import dev.erezalkalay.jobhunter.job.dto.JobRequest;
import dev.erezalkalay.jobhunter.job.dto.JobResponse;
import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read and edit one of the current user's jobs. Jobs are created only together
 * with their Board ticket (POST /api/board/jobs, or the MCP integration) and
 * deleted with it (DELETE /api/applications/{id}).
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final JobMatchRepository jobMatchRepository;

    public JobController(JobService jobService, JobMatchRepository jobMatchRepository) {
        this.jobService = jobService;
        this.jobMatchRepository = jobMatchRepository;
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable Long id) {
        return withFitScore(jobService.getJobForCurrentUser(id));
    }

    @PatchMapping("/{id}")
    public JobResponse updateJob(@PathVariable Long id, @Valid @RequestBody JobRequest request) {
        return withFitScore(jobService.updateJobForCurrentUser(id, request));
    }

    // The job was resolved for the current user, so its owner is the caller.
    private JobResponse withFitScore(Job job) {
        BigDecimal fitScore = jobMatchRepository.findByJobIdAndUserId(job.getId(), job.getUser().getId())
                .map(JobMatch::getMatchScore)
                .orElse(null);
        return JobResponse.from(job, fitScore);
    }
}
