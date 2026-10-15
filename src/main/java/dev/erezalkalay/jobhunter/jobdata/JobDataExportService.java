package dev.erezalkalay.jobhunter.jobdata;

import dev.erezalkalay.jobhunter.application.Application;
import dev.erezalkalay.jobhunter.application.ApplicationRepository;
import dev.erezalkalay.jobhunter.application.ApplicationStage;
import dev.erezalkalay.jobhunter.application.ApplicationStageRepository;
import dev.erezalkalay.jobhunter.application.ApplicationStatusHistory;
import dev.erezalkalay.jobhunter.application.ApplicationStatusHistoryRepository;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.company.Company;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedAiMatch;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedApplication;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedCompany;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedJob;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedStage;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedStatusChange;
import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the current user's job data export: every one of the user's jobs
 * (each has exactly one Application) with its company, stages, status history
 * and AI match. Every query is scoped to the current user, so no other user's
 * row can appear. Ordering is deterministic (by creation time, then insertion
 * order) so exporting the same data twice yields the same file.
 */
@Service
@Transactional(readOnly = true)
public class JobDataExportService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationStageRepository stageRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final JobMatchRepository jobMatchRepository;
    private final CurrentUserProvider currentUserProvider;

    public JobDataExportService(
            ApplicationRepository applicationRepository,
            ApplicationStageRepository stageRepository,
            ApplicationStatusHistoryRepository historyRepository,
            JobMatchRepository jobMatchRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.applicationRepository = applicationRepository;
        this.stageRepository = stageRepository;
        this.historyRepository = historyRepository;
        this.jobMatchRepository = jobMatchRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public JobDataV1 exportForCurrentUser() {
        Long userId = currentUserProvider.getCurrentUser().getId();

        List<Application> applications = applicationRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .sorted(Comparator.comparing(Application::getCreatedAt).thenComparing(Application::getId))
                .toList();
        Map<Long, List<ApplicationStage>> stagesByApplication = stageRepository.findAllByApplication_User_Id(userId).stream()
                .sorted(Comparator.comparing(ApplicationStage::getCreatedAt).thenComparing(ApplicationStage::getId))
                .collect(Collectors.groupingBy(stage -> stage.getApplication().getId()));
        Map<Long, List<ApplicationStatusHistory>> historyByApplication = historyRepository.findAllByApplication_User_Id(userId).stream()
                .sorted(Comparator.comparing(ApplicationStatusHistory::getEnteredAt).thenComparing(ApplicationStatusHistory::getId))
                .collect(Collectors.groupingBy(entry -> entry.getApplication().getId()));
        // The user's own matches; only those for jobs the user tracks are exported.
        Map<Long, JobMatch> matchesByJob = jobMatchRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(match -> match.getJob().getId(), Function.identity()));

        List<ExportedJob> jobs = new ArrayList<>();
        for (Application application : applications) {
            Job job = application.getJob();
            jobs.add(toExportedJob("job-" + (jobs.size() + 1), job, application,
                    stagesByApplication.getOrDefault(application.getId(), List.of()),
                    historyByApplication.getOrDefault(application.getId(), List.of()),
                    matchesByJob.get(job.getId())));
        }
        return new JobDataV1(JobDataV1.FORMAT, JobDataV1.VERSION, Instant.now(), jobs);
    }

    private static ExportedJob toExportedJob(String ref, Job job, Application application, List<ApplicationStage> stages,
                                             List<ApplicationStatusHistory> history, JobMatch match) {
        return new ExportedJob(
                ref,
                job.getTitle(),
                toExportedCompany(job.getCompany()),
                job.getDescription(),
                job.isDescriptionDiscarded(),
                job.getLocation(),
                job.getWorkModel(),
                job.getUrl(),
                job.getSalaryMin(),
                job.getSalaryMax(),
                job.getCurrency(),
                job.getSource(),
                job.getExternalId(),
                job.getDiscoveredAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                new ExportedApplication(
                        application.getStatus().getCode(),
                        application.getBoardPosition(),
                        application.isStarred(),
                        application.getNotes(),
                        application.getAppliedAt(),
                        application.getCreatedAt(),
                        application.getUpdatedAt(),
                        history.stream().map(entry -> new ExportedStatusChange(
                                entry.getStatus().getCode(), entry.getEnteredAt(), entry.getNotes())).toList(),
                        stages.stream().map(stage -> new ExportedStage(
                                stage.getName(), stage.getScheduledAt(), stage.getInterviewerNames(), stage.getNotes(),
                                stage.getOutcome(), stage.getCreatedAt(), stage.getUpdatedAt())).toList()),
                match == null ? null : new ExportedAiMatch(
                        match.getMatchScore(),
                        match.getMatchingSkills(),
                        match.getMissingSkills(),
                        match.getConcerns(),
                        match.getProvider(),
                        match.getModel(),
                        match.getEvaluatedAt(),
                        match.getCreatedAt()));
    }

    private static ExportedCompany toExportedCompany(Company company) {
        return company == null ? null : new ExportedCompany(company.getName(), company.getWebsite(), company.getNotes());
    }
}
