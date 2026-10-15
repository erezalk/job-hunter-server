package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.board.BoardService;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.jobmatch.ImportedMatch;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bearer-token entry point to the SAME "add job and track" operation the
 * Board's Add Job button uses ({@link BoardService#addAndTrackJob}). It only
 * authenticates (see IntegrationSecurityConfig) and maps the result; the owner
 * is always the token's user and the body has no owner field. Jobs created
 * here get source "External AI"; an optional already-calculated fit result is
 * stored as a JobMatch attributed to External AI (no AI provider is called).
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationJobController {

    public record AddJobResponse(
            boolean created, Long applicationId, Long jobId, String title, String companyName, String statusCode, String statusLabel,
            Integer importedFitScore) {
    }

    private final BoardService boardService;

    public IntegrationJobController(BoardService boardService) {
        this.boardService = boardService;
    }

    @PostMapping("/jobs")
    public ResponseEntity<AddJobResponse> addJob(@Valid @RequestBody IntegrationAddJobRequest request) {
        // Validated before anything is written, so a bad evaluation never leaves a half-added job.
        ImportedMatch imported = request.toImportedMatch();
        BoardService.AddJobResult result = boardService.addAndTrackJob(request.toJobRequest(), Job.SOURCE_EXTERNAL_AI, imported);
        var application = result.application();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new AddJobResponse(
                        result.created(),
                        application.getId(),
                        application.getJob().getId(),
                        application.getJob().getTitle(),
                        application.getJob().getCompany() == null ? null : application.getJob().getCompany().getName(),
                        application.getStatus().getCode(),
                        application.getStatus().getLabel(),
                        result.importedMatch() == null ? null : result.importedMatch().getMatchScore().intValue()));
    }
}
