package dev.erezalkalay.jobhunter.application;

import dev.erezalkalay.jobhunter.application.dto.ApplicationStageRequest;
import dev.erezalkalay.jobhunter.application.dto.MoveRequest;
import dev.erezalkalay.jobhunter.application.dto.UpcomingStageResponse;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.IllegalStatusTransitionException;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.job.JobService;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Board ticket of a job, with its status history and stages. Every public
 * method resolves the Application for the current user first (another user's
 * id is reported as not found, same as a missing one); history and stages are
 * only ever reached through that already verified Application.
 */
@Service
@Transactional(readOnly = true)
public class ApplicationService {

    private static final String INITIAL_STATUS_CODE = "NEW";
    private static final String LOW_MATCH_STATUS_CODE = "LOW_MATCH";

    private final ApplicationRepository applicationRepository;
    private final ApplicationStageRepository applicationStageRepository;
    private final ApplicationStatusHistoryRepository applicationStatusHistoryRepository;
    private final ApplicationStatusRepository applicationStatusRepository;
    private final JobService jobService;
    private final JobMatchRepository jobMatchRepository;
    private final CurrentUserProvider currentUserProvider;

    public ApplicationService(
            ApplicationRepository applicationRepository,
            ApplicationStageRepository applicationStageRepository,
            ApplicationStatusHistoryRepository applicationStatusHistoryRepository,
            ApplicationStatusRepository applicationStatusRepository,
            JobService jobService,
            JobMatchRepository jobMatchRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationStageRepository = applicationStageRepository;
        this.applicationStatusHistoryRepository = applicationStatusHistoryRepository;
        this.applicationStatusRepository = applicationStatusRepository;
        this.jobService = jobService;
        this.jobMatchRepository = jobMatchRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Creates the Board ticket (in NEW) of a job the current user just created.
     * Every job has exactly one Application, owned by the job's user; anything
     * else is a programming error, refused here and by the database.
     */
    @Transactional
    public Application trackNewJob(Job job) {
        User user = currentUserProvider.getCurrentUser();
        if (!job.getUser().getId().equals(user.getId())) {
            throw new IllegalStateException("A job can only be tracked by its owner");
        }
        return createInitialApplication(job, user);
    }

    private Application createInitialApplication(Job job, User user) {
        ApplicationStatus initialStatus = getStatus(INITIAL_STATUS_CODE);
        Application application = new Application(job, user, initialStatus);
        application.setBoardPosition(topPosition(user.getId(), INITIAL_STATUS_CODE));
        application = applicationRepository.save(application);
        applicationStatusHistoryRepository.save(new ApplicationStatusHistory(application, initialStatus, "Application created"));
        return application;
    }

    /**
     * Authoritative LOW_MATCH workflow rule, applied by the AI evaluation
     * service after a SUCCESSFUL evaluation of one of the owner's jobs. Returns
     * true when the job's ticket is LOW_MATCH afterwards, i.e. the caller must
     * run the low-match data cleanup. Only the owner's own ticket is involved.
     *
     * <ul>
     *   <li>below threshold: NEW becomes LOW_MATCH; an already LOW_MATCH one
     *       stays. Any other status is an explicit user workflow decision and
     *       is left alone (and nothing is discarded).</li>
     *   <li>meets threshold: LOW_MATCH returns to NEW; every other status stays.</li>
     * </ul>
     * Transitions go through the normal status history, with a note saying the
     * system (not the user) made the change.
     */
    @Transactional
    public boolean applyMatchOutcome(Job job, boolean meetsThreshold, int fitScore, int minimumFitScore) {
        Long ownerId = job.getUser().getId();
        Application application = applicationRepository.findByJobIdAndUserId(job.getId(), ownerId)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", job.getId()));
        String code = application.getStatus().getCode();
        if (meetsThreshold) {
            if (LOW_MATCH_STATUS_CODE.equals(code)) {
                transition(application, INITIAL_STATUS_CODE,
                        "Automatic: AI fit " + fitScore + "% meets the " + minimumFitScore + "% minimum");
            }
            return false;
        }
        if (INITIAL_STATUS_CODE.equals(code)) {
            transition(application, LOW_MATCH_STATUS_CODE,
                    "Automatic: low AI match, fit " + fitScore + "% is below the " + minimumFitScore + "% minimum");
            return true;
        }
        return LOW_MATCH_STATUS_CODE.equals(code);
    }

    public List<Application> listApplicationsForCurrentUser(String statusCode) {
        User user = currentUserProvider.getCurrentUser();
        if (statusCode != null && !statusCode.isBlank()) {
            return applicationRepository.findAllByUserIdAndStatus_CodeOrderByCreatedAtDesc(user.getId(), statusCode);
        }
        return applicationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());
    }

    /** The current user's Application; another user's is reported as not found, same as a missing id. */
    public Application getApplication(Long id) {
        User user = currentUserProvider.getCurrentUser();
        return applicationRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));
    }

    public List<ApplicationStatusHistory> getStatusHistory(Long applicationId) {
        getApplication(applicationId);
        return applicationStatusHistoryRepository.findAllByApplicationIdOrderByEnteredAtAsc(applicationId);
    }

    public List<ApplicationStatus> listStatuses() {
        return applicationStatusRepository.findAllByOrderBySortOrderAsc();
    }

    /**
     * Moves an Application to any board status. There is no rigid workflow
     * or terminal-status lock: this behaves like dragging a
     * Jira ticket to any column, in either direction (e.g. REJECTED -> NEW).
     * Dropping a card back onto its current column is a harmless no-op.
     */
    @Transactional
    public Application changeStatus(Long applicationId, String newStatusCode, String notes) {
        return transition(getApplication(applicationId), newStatusCode, notes);
    }

    private Application transition(Application application, String newStatusCode, String notes) {
        ApplicationStatus currentStatus = application.getStatus();
        ApplicationStatus newStatus = getStatus(newStatusCode);

        if (newStatus.getCode().equals(currentStatus.getCode())) {
            return application;
        }

        application.setStatus(newStatus);
        application.setBoardPosition(topPosition(application.getUser().getId(), newStatus.getCode()));
        if ("CV_SENT".equals(newStatus.getCode()) && application.getAppliedAt() == null) {
            application.setAppliedAt(Instant.now());
        }

        applicationStatusHistoryRepository.save(new ApplicationStatusHistory(application, newStatus, notes));
        return application;
    }

    // Tickets entering a column (new, status change) land on top, which is where
    // the Board has always shown the newest ticket. Explicit drops override this.
    private int topPosition(Long userId, String statusCode) {
        return applicationRepository.minBoardPosition(userId, statusCode) - 1;
    }

    /**
     * The Board drop: moves the current user's Application to {@code statusCode}
     * (status history only when the status really changes) and places it
     * before/after an anchor ticket of that column, or at the end. The whole
     * destination column is resequenced 0..n-1 under a lock, so relative order
     * of tickets the client did not see (filtered out) is preserved.
     */
    @Transactional
    public Application moveApplication(Long applicationId, MoveRequest request) {
        User user = currentUserProvider.getCurrentUser();
        Application application = applicationRepository.findByIdAndUserId(applicationId, user.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));
        if (request.beforeApplicationId() != null && request.afterApplicationId() != null) {
            throw new InvalidRequestException("Specify at most one of beforeApplicationId and afterApplicationId");
        }
        String targetCode = getStatus(request.statusCode()).getCode();
        Long anchorId = request.beforeApplicationId() != null ? request.beforeApplicationId() : request.afterApplicationId();
        if (applicationId.equals(anchorId)) {
            throw new InvalidRequestException("A ticket cannot be positioned relative to itself");
        }

        transition(application, targetCode, null);

        List<Application> column = new ArrayList<>(applicationRepository.lockColumn(user.getId(), targetCode));
        column.removeIf(other -> other.getId().equals(applicationId));
        int index = column.size();
        if (anchorId != null) {
            int anchorIndex = -1;
            for (int i = 0; i < column.size(); i++) {
                if (column.get(i).getId().equals(anchorId)) {
                    anchorIndex = i;
                }
            }
            if (anchorIndex < 0) {
                throw new InvalidRequestException("The reference ticket is not in the destination column");
            }
            index = request.beforeApplicationId() != null ? anchorIndex : anchorIndex + 1;
        }
        column.add(index, application);
        for (int i = 0; i < column.size(); i++) {
            if (column.get(i).getBoardPosition() != i) {
                column.get(i).setBoardPosition(i);
            }
        }
        return application;
    }

    @Transactional
    public Application updateStarred(Long applicationId, boolean starred) {
        User user = currentUserProvider.getCurrentUser();
        Application application = applicationRepository.findByIdAndUserId(applicationId, user.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));
        application.setStarred(starred);
        return application;
    }

    /**
     * Permanently deletes the current user's ticket and its job: stages,
     * status history, AI match, Application, Job and — if no other job of the
     * user uses it — the company. Everything involved belongs to the caller;
     * another user's id is reported as not found, same as a missing id.
     */
    @Transactional
    public void deleteApplication(Long applicationId) {
        User user = currentUserProvider.getCurrentUser();
        Application application = applicationRepository.findByIdAndUserId(applicationId, user.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));
        Job job = application.getJob();

        applicationStageRepository.deleteAllByApplicationId(applicationId);
        applicationStatusHistoryRepository.deleteAllByApplicationId(applicationId);
        jobMatchRepository.deleteByJobIdAndUserId(job.getId(), user.getId());
        applicationRepository.delete(application);
        applicationRepository.flush();
        jobService.deleteJob(job);
    }

    @Transactional
    public Application updateNotes(Long applicationId, String notes) {
        Application application = getApplication(applicationId);
        application.setNotes(notes);
        return application;
    }

    public List<ApplicationStage> getStages(Long applicationId) {
        getApplication(applicationId);
        return applicationStageRepository.findAllByApplicationIdOrderedForDisplay(applicationId);
    }

    /** Every future scheduled stage of the current user's applications, nearest first. */
    public List<UpcomingStageResponse> getUpcomingStages() {
        User user = currentUserProvider.getCurrentUser();
        return applicationStageRepository.findUpcomingForUser(user.getId(), Instant.now());
    }

    @Transactional
    public ApplicationStage addStage(Long applicationId, ApplicationStageRequest request) {
        Application application = getApplication(applicationId);
        ApplicationStage stage = new ApplicationStage(application, request.name());
        applyStageFields(stage, request);
        return applicationStageRepository.save(stage);
    }

    @Transactional
    public ApplicationStage updateStage(Long applicationId, Long stageId, ApplicationStageRequest request) {
        ApplicationStage stage = getStageOwnedByApplication(applicationId, stageId);
        stage.setName(request.name());
        applyStageFields(stage, request);
        return stage;
    }

    @Transactional
    public void deleteStage(Long applicationId, Long stageId) {
        ApplicationStage stage = getStageOwnedByApplication(applicationId, stageId);
        applicationStageRepository.delete(stage);
    }

    private void applyStageFields(ApplicationStage stage, ApplicationStageRequest request) {
        stage.setScheduledAt(request.scheduledAt());
        stage.setInterviewerNames(request.interviewerNames());
        stage.setNotes(request.notes());
        stage.setOutcome(request.outcome());
    }

    // A stage id only resolves inside the given application of the current user.
    private ApplicationStage getStageOwnedByApplication(Long applicationId, Long stageId) {
        Application application = getApplication(applicationId);
        return applicationStageRepository
                .findByIdAndApplication_IdAndApplication_User_Id(stageId, application.getId(), application.getUser().getId())
                .orElseThrow(() -> ResourceNotFoundException.of("ApplicationStage", stageId));
    }

    private ApplicationStatus getStatus(String code) {
        return applicationStatusRepository.findById(code)
                .orElseThrow(() -> new IllegalStatusTransitionException("Unknown application status code: " + code));
    }
}
