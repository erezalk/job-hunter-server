package dev.erezalkalay.jobhunter.jobdata;

import dev.erezalkalay.jobhunter.application.Application;
import dev.erezalkalay.jobhunter.application.ApplicationRepository;
import dev.erezalkalay.jobhunter.application.ApplicationStage;
import dev.erezalkalay.jobhunter.application.ApplicationStageRepository;
import dev.erezalkalay.jobhunter.application.ApplicationStatus;
import dev.erezalkalay.jobhunter.application.ApplicationStatusHistory;
import dev.erezalkalay.jobhunter.application.ApplicationStatusHistoryRepository;
import dev.erezalkalay.jobhunter.application.ApplicationStatusRepository;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.company.Company;
import dev.erezalkalay.jobhunter.company.CompanyRepository;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.job.JobRepository;
import dev.erezalkalay.jobhunter.job.JobService;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedAiMatch;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedApplication;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedJob;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedStage;
import dev.erezalkalay.jobhunter.jobdata.JobDataV1.ExportedStatusChange;
import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.jobmatch.ai.ProviderId;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restores a job data export into the current user's (empty) job domain.
 *
 * <p>Version 1 semantics: the whole file is validated first; the import is
 * refused (409) unless the current user has no jobs, companies, Applications or
 * AI matches yet. Other users' data plays no part: it is never read, matched
 * or changed, so another user tracking the same company + title does not block
 * the restore. Everything is then written in one transaction — any failure
 * rolls the whole import back — while the user's row is locked, so two imports
 * of the same user cannot interleave.
 *
 * <p>This is a restore, not normal Board activity: records are created
 * directly (no status transitions, no automatic history rows, no AI calls),
 * exported timestamps are written back as they were, and derived values
 * (normalized company/title/URL keys) are recomputed with the same code the
 * application uses. Every created row belongs to the authenticated user; the
 * file cannot name an owner.
 */
@Service
public class JobDataImportService {

    public record ImportSummary(int jobs, int stages, int statusHistoryEntries, int aiMatches) {
    }

    private static final Set<String> PROVIDERS = Stream.concat(
            Stream.of(ProviderId.values()).map(Enum::name), Stream.of(JobMatch.PROVIDER_EXTERNAL_AI))
            .collect(Collectors.toUnmodifiableSet());
    private static final Pattern URL_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationStatusRepository statusRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final ApplicationStageRepository stageRepository;
    private final JobMatchRepository jobMatchRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Validator validator;
    private final JdbcTemplate jdbcTemplate;

    public JobDataImportService(
            JobRepository jobRepository,
            CompanyRepository companyRepository,
            ApplicationRepository applicationRepository,
            ApplicationStatusRepository statusRepository,
            ApplicationStatusHistoryRepository historyRepository,
            ApplicationStageRepository stageRepository,
            JobMatchRepository jobMatchRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            Validator validator,
            JdbcTemplate jdbcTemplate
    ) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.applicationRepository = applicationRepository;
        this.statusRepository = statusRepository;
        this.historyRepository = historyRepository;
        this.stageRepository = stageRepository;
        this.jobMatchRepository = jobMatchRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.validator = validator;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ImportSummary importForCurrentUser(JobDataV1 data) {
        User user = userRepository.lockById(currentUserProvider.getCurrentUser().getId()).orElseThrow();
        Map<String, ApplicationStatus> statuses = statusRepository.findAll().stream()
                .collect(Collectors.toMap(ApplicationStatus::getCode, status -> status));
        validate(data, statuses.keySet());
        requireEmptyJobDomain(user);
        return restore(data, user, statuses);
    }

    // --- Validation (nothing is written until the whole file passed) ---

    private void validate(JobDataV1 data, Set<String> statusCodes) {
        if (!JobDataV1.FORMAT.equals(data.format())) {
            throw new ImportValidationException(Map.of("format", "must be \"" + JobDataV1.FORMAT + "\""));
        }
        if (!Integer.valueOf(JobDataV1.VERSION).equals(data.formatVersion())) {
            throw new ImportValidationException(Map.of("formatVersion",
                    "unsupported version " + data.formatVersion() + " (supported: " + JobDataV1.VERSION + ")"));
        }

        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<JobDataV1> violation : validator.validate(data)) {
            errors.putIfAbsent(violation.getPropertyPath().toString().replace(".<list element>", ""), violation.getMessage());
        }
        if (data.jobs() != null) {
            Set<String> refs = new HashSet<>();
            Map<String, Integer> identities = new HashMap<>();
            Map<String, Integer> companies = new HashMap<>();
            for (int i = 0; i < data.jobs().size(); i++) {
                ExportedJob job = data.jobs().get(i);
                if (job != null) {
                    validateJob("jobs[" + i + "]", i, job, statusCodes, refs, identities, errors);
                    validateCompany("jobs[" + i + "]", i, data.jobs(), companies, errors);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportValidationException(errors);
        }
    }

    private static void validateJob(String at, int index, ExportedJob job, Set<String> statusCodes, Set<String> refs,
                                    Map<String, Integer> identities, Map<String, String> errors) {
        if (job.ref() != null && !refs.add(job.ref())) {
            errors.putIfAbsent(at + ".ref", "duplicate ref \"" + job.ref() + "\"");
        }
        if (job.title() != null) {
            // The same identity the database enforces (normalized company + title).
            Integer first = identities.putIfAbsent(identity(job), index);
            if (first != null) {
                errors.putIfAbsent(at + ".title", "same company and title as jobs[" + first + "]");
            }
        }
        if (job.salaryMin() != null && job.salaryMax() != null && job.salaryMin() > job.salaryMax()) {
            errors.putIfAbsent(at + ".salaryMin", "must not be greater than salaryMax");
        }
        String url = job.url();
        if (url != null && !url.isEmpty()) {
            boolean whitespace = url.chars().anyMatch(Character::isWhitespace);
            String lower = url.toLowerCase(Locale.ROOT);
            boolean otherScheme = URL_SCHEME.matcher(url).find() && !lower.startsWith("http://") && !lower.startsWith("https://");
            if (whitespace || otherScheme) {
                errors.putIfAbsent(at + ".url", "must be an http(s) URL");
            }
        }
        ExportedApplication application = job.application();
        if (application != null) {
            if (application.status() != null && !statusCodes.contains(application.status())) {
                errors.putIfAbsent(at + ".application.status", "unknown status \"" + application.status() + "\"");
            }
            List<ExportedStatusChange> history = application.statusHistory();
            for (int h = 0; history != null && h < history.size(); h++) {
                ExportedStatusChange change = history.get(h);
                if (change != null && change.status() != null && !statusCodes.contains(change.status())) {
                    errors.putIfAbsent(at + ".application.statusHistory[" + h + "].status",
                            "unknown status \"" + change.status() + "\"");
                }
            }
        }
        ExportedAiMatch match = job.aiMatch();
        if (match != null && match.provider() != null && !PROVIDERS.contains(match.provider())) {
            errors.putIfAbsent(at + ".aiMatch.provider", "must be one of " + PROVIDERS.stream().sorted().toList());
        }
    }

    // Companies are user-owned rows shared by the user's jobs with the same
    // normalized name, so every job naming that company must carry the same
    // company data — anything else could only be restored by silently
    // dropping some of it. (A real export always satisfies this.)
    private static void validateCompany(String at, int index, List<ExportedJob> jobs, Map<String, Integer> companies,
                                        Map<String, String> errors) {
        JobDataV1.ExportedCompany company = jobs.get(index).company();
        if (company == null || company.name() == null) {
            return;
        }
        Integer first = companies.putIfAbsent(JobService.normalizeKeyPart(company.name()), index);
        if (first != null && !Objects.equals(company, jobs.get(first).company())) {
            errors.putIfAbsent(at + ".company", "differs from the company of jobs[" + first + "] with the same name");
        }
    }

    // Only the current user's own rows count; other users' data is irrelevant.
    private void requireEmptyJobDomain(User user) {
        Long userId = user.getId();
        if (jobRepository.existsByUserId(userId) || applicationRepository.existsByUserId(userId)
                || jobMatchRepository.existsByUserId(userId) || companyRepository.existsByUserId(userId)) {
            throw new ImportConflictException("Import needs an empty Board, but you already have job data. "
                    + "Nothing was imported or changed.");
        }
    }

    // --- Restore (one transaction) ---

    private ImportSummary restore(JobDataV1 data, User user, Map<String, ApplicationStatus> statuses) {
        List<Object[]> jobTimes = new ArrayList<>();
        List<Object[]> applicationTimes = new ArrayList<>();
        List<Object[]> historyTimes = new ArrayList<>();
        List<Object[]> stageTimes = new ArrayList<>();
        List<Object[]> matchTimes = new ArrayList<>();
        int stageCount = 0;
        int historyCount = 0;
        int matchCount = 0;

        Map<String, Company> companies = new HashMap<>();
        for (ExportedJob exported : data.jobs()) {
            Job job = jobRepository.save(newJob(exported, user, companies));
            jobTimes.add(new Object[] {ts(exported.discoveredAt()), ts(exported.createdAt()), ts(exported.updatedAt()),
                    job.getId(), user.getId()});

            ExportedApplication exportedApplication = exported.application();
            Application application = new Application(job, user, statuses.get(exportedApplication.status()));
            application.setBoardPosition(exportedApplication.boardPosition());
            application.setStarred(exportedApplication.starred());
            application.setNotes(exportedApplication.notes());
            application.setAppliedAt(exportedApplication.appliedAt());
            application = applicationRepository.save(application);
            applicationTimes.add(new Object[] {ts(exportedApplication.createdAt()), ts(exportedApplication.updatedAt()),
                    application.getId(), user.getId()});

            for (ExportedStatusChange change : exportedApplication.statusHistory()) {
                ApplicationStatusHistory entry = historyRepository.save(
                        new ApplicationStatusHistory(application, statuses.get(change.status()), change.notes()));
                historyTimes.add(new Object[] {ts(change.enteredAt()), entry.getId()});
                historyCount++;
            }
            for (ExportedStage exportedStage : exportedApplication.stages()) {
                ApplicationStage stage = new ApplicationStage(application, exportedStage.name());
                stage.setScheduledAt(exportedStage.scheduledAt());
                stage.setInterviewerNames(exportedStage.interviewerNames());
                stage.setNotes(exportedStage.notes());
                stage.setOutcome(exportedStage.outcome());
                stage = stageRepository.save(stage);
                stageTimes.add(new Object[] {ts(exportedStage.createdAt()), ts(exportedStage.updatedAt()), stage.getId()});
                stageCount++;
            }
            if (exported.aiMatch() != null) {
                JobMatch match = jobMatchRepository.save(newMatch(exported.aiMatch(), job));
                matchTimes.add(new Object[] {ts(exported.aiMatch().createdAt()), match.getId(), user.getId()});
                matchCount++;
            }
        }

        // Write the exported timestamps back over the ones auditing/constructors
        // just set. Flushed first, so nothing is left dirty to re-stamp them.
        jobRepository.flush();
        jdbcTemplate.batchUpdate("UPDATE jobs SET discovered_at = ?, created_at = ?, updated_at = ? WHERE id = ? AND user_id = ?", jobTimes);
        jdbcTemplate.batchUpdate("UPDATE applications SET created_at = ?, updated_at = ? WHERE id = ? AND user_id = ?", applicationTimes);
        jdbcTemplate.batchUpdate("UPDATE application_status_history SET entered_at = ? WHERE id = ?", historyTimes);
        jdbcTemplate.batchUpdate("UPDATE application_stages SET created_at = ?, updated_at = ? WHERE id = ?", stageTimes);
        jdbcTemplate.batchUpdate("UPDATE job_matches SET created_at = ? WHERE id = ? AND user_id = ?", matchTimes);

        return new ImportSummary(data.jobs().size(), stageCount, historyCount, matchCount);
    }

    private Job newJob(ExportedJob exported, User user, Map<String, Company> companies) {
        Job job = new Job(user, exported.title());
        if (exported.company() != null) {
            job.setCompany(companyFor(exported.company(), user, companies));
        }
        job.setDescription(exported.description());
        job.setDescriptionDiscarded(exported.descriptionDiscarded());
        job.setLocation(exported.location());
        job.setWorkModel(exported.workModel());
        job.setUrl(exported.url());
        job.setSalaryMin(exported.salaryMin());
        job.setSalaryMax(exported.salaryMax());
        job.setCurrency(exported.currency());
        job.setSource(exported.source());
        job.setExternalId(exported.externalId());
        // Derived values, recomputed exactly as on create.
        job.setNormalizedUrl(JobService.normalizeUrl(exported.url()));
        job.setCompanyKey(JobService.normalizeKeyPart(companyName(exported)));
        job.setTitleKey(JobService.normalizeKeyPart(exported.title()));
        return job;
    }

    // One new company of the importing user per normalized name (validation
    // guaranteed that all jobs naming it carry the same company data).
    private Company companyFor(JobDataV1.ExportedCompany exported, User user, Map<String, Company> companies) {
        String nameKey = JobService.normalizeKeyPart(exported.name());
        return companies.computeIfAbsent(nameKey, key -> {
            Company company = new Company(user, exported.name(), key);
            company.setWebsite(exported.website());
            company.setNotes(exported.notes());
            return companyRepository.save(company);
        });
    }

    // No CV reference and no fingerprint: both identified rows of the exporting
    // database. Without a fingerprint the match is shown as stored but never
    // treated as a reusable cache hit; the next non-forced evaluation re-runs it.
    private static JobMatch newMatch(ExportedAiMatch exported, Job job) {
        JobMatch match = new JobMatch(job);
        match.setMatchScore(exported.fitScore());
        match.setMatchingSkills(exported.matchingSkills());
        match.setMissingSkills(exported.missingSkills());
        match.setConcerns(exported.concerns());
        match.setProvider(exported.provider());
        match.setModel(exported.model());
        match.setEvaluatedAt(exported.evaluatedAt());
        return match;
    }

    private static String companyName(ExportedJob job) {
        return job.company() != null ? job.company().name() : null;
    }

    private static String identity(ExportedJob job) {
        return JobService.normalizeKeyPart(companyName(job)) + "\u0000" + JobService.normalizeKeyPart(job.title());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
