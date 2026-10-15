package dev.erezalkalay.jobhunter.board;

import dev.erezalkalay.jobhunter.application.Application;
import dev.erezalkalay.jobhunter.application.ApplicationRepository;
import dev.erezalkalay.jobhunter.application.ApplicationService;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.board.dto.BoardCardResponse;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.job.JobService;
import dev.erezalkalay.jobhunter.job.dto.JobRequest;
import dev.erezalkalay.jobhunter.jobmatch.ImportedMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchService;
import dev.erezalkalay.jobhunter.user.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional(readOnly = true)
public class BoardService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationService applicationService;
    private final JobService jobService;
    private final JobMatchRepository jobMatchRepository;
    private final JobMatchService jobMatchService;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate transactionTemplate;

    public BoardService(
            ApplicationRepository applicationRepository,
            ApplicationService applicationService,
            JobService jobService,
            JobMatchRepository jobMatchRepository,
            JobMatchService jobMatchService,
            CurrentUserProvider currentUserProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationService = applicationService;
        this.jobService = jobService;
        this.jobMatchRepository = jobMatchRepository;
        this.jobMatchService = jobMatchService;
        this.currentUserProvider = currentUserProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<BoardCardResponse> loadBoard() {
        User user = currentUserProvider.getCurrentUser();
        List<Application> applications = applicationRepository.findAllByUserIdOrderByBoardPositionAscIdDesc(user.getId());

        List<Long> jobIds = applications.stream().map(a -> a.getJob().getId()).toList();
        Map<Long, BigDecimal> fitScoresByJobId = jobMatchRepository.findAllByUserIdAndJobIdIn(user.getId(), jobIds).stream()
                .collect(Collectors.toMap(m -> m.getJob().getId(), JobMatch::getMatchScore, (a, b) -> a));

        return applications.stream()
                .map(application -> BoardCardResponse.from(application, fitScoresByJobId.get(application.getJob().getId())))
                .toList();
    }

    /** {@code importedMatch} is non-null only when an external evaluation was actually stored by this call. */
    public record AddJobResult(Application application, boolean created, JobMatch importedMatch) {

        public AddJobResult(Application application, boolean created) {
            this(application, created, null);
        }
    }

    /**
     * "Add job and track" as one user-level operation: finds the current
     * user's own job (deduplicated on normalized company + title within their
     * jobs only) or creates it together with its Application in NEW.
     * {@code created} is false when the job was already on the user's Board.
     * Other users' jobs are never looked at, so the same company + title can be
     * tracked independently by every user. If a concurrent request of the same
     * user wins the race to insert the same job or company, the unique index
     * rejects ours and the retry in a fresh transaction finds theirs.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AddJobResult addAndTrackJob(JobRequest request) {
        return addAndTrackJob(request, Job.SOURCE_MANUAL, null);
    }

    /**
     * Same operation for a caller that assigns the job's source itself and may
     * carry an already-calculated external evaluation (MCP). The source only
     * applies to a newly created job. The evaluation (if any) is stored in the
     * same transaction as the job and application, but only for a ticket this
     * call actually created and only when the user has no match for the job
     * yet — a duplicate never creates or overwrites a match. No AI provider is
     * called and the Application keeps its initial status whatever the score.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AddJobResult addAndTrackJob(JobRequest request, String source, ImportedMatch importedMatch) {
        try {
            return transactionTemplate.execute(status -> addAndTrack(request, source, importedMatch));
        } catch (DataIntegrityViolationException raced) {
            return transactionTemplate.execute(status -> addAndTrack(request, source, importedMatch));
        }
    }

    private AddJobResult addAndTrack(JobRequest request, String source, ImportedMatch importedMatch) {
        User user = currentUserProvider.getCurrentUser();
        Job job = jobService.findOrCreateJob(user, request, source, null);
        // Every job has exactly one Application, so an existing job is already on the Board.
        return applicationRepository.findByJobIdAndUserId(job.getId(), user.getId())
                .map(existing -> new AddJobResult(existing, false))
                .orElseGet(() -> {
                    Application application = applicationService.trackNewJob(job);
                    JobMatch imported = importedMatch == null ? null
                            : jobMatchService.importExternalMatch(job, importedMatch).orElse(null);
                    return new AddJobResult(application, true, imported);
                });
    }
}
