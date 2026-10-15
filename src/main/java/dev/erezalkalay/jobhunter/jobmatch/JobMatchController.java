package dev.erezalkalay.jobhunter.jobmatch;

import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.jobmatch.dto.JobMatchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs/{jobId}/match")
public class JobMatchController {

    private final JobMatchService jobMatchService;

    public JobMatchController(JobMatchService jobMatchService) {
        this.jobMatchService = jobMatchService;
    }

    @GetMapping
    public JobMatchResponse getMatch(@PathVariable Long jobId) {
        return jobMatchService.getForCurrentUser(jobId)
                .map(JobMatchResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("This job has not been evaluated yet"));
    }

    @PostMapping("/evaluate")
    public JobMatchResponse evaluate(@PathVariable Long jobId, @RequestParam(defaultValue = "false") boolean force) {
        return JobMatchResponse.from(jobMatchService.evaluateForCurrentUser(jobId, force));
    }
}
