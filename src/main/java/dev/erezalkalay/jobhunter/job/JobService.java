package dev.erezalkalay.jobhunter.job;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.DuplicateJobException;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.company.Company;
import dev.erezalkalay.jobhunter.company.CompanyRepository;
import dev.erezalkalay.jobhunter.job.dto.JobRequest;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.user.User;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Jobs and their companies are owned by one user. Every method here either
 * resolves the job through the current user ({@link #getJobForCurrentUser}) or
 * receives the owner explicitly from a caller that did; lookups, deduplication
 * and company reuse never look at another user's rows.
 */
@Service
@Transactional(readOnly = true)
public class JobService {

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final JobMatchRepository jobMatchRepository;
    private final CurrentUserProvider currentUserProvider;

    public JobService(
            JobRepository jobRepository,
            CompanyRepository companyRepository,
            JobMatchRepository jobMatchRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.jobMatchRepository = jobMatchRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Edits the user-editable job information (the same fields as Add Job) of
     * one of the current user's jobs (anything else is reported as not found).
     * Identity keys and the normalized URL are recomputed with the same
     * normalization used on create; an edit that would collide with another of
     * the user's jobs is rejected with 409 and nothing is merged or deleted.
     * Source is overwritten with the submitted free text (blank clears it);
     * external id, discovered date, the Application, status history and notes
     * are never touched. A company no longer used by any of the user's jobs is
     * removed.
     *
     * If the edit changes the input the AI evaluates (title, company,
     * location, work model, description) the job's now-stale JobMatch is
     * removed — no AI call is made; the user re-evaluates explicitly.
     * Other edits (URL, salary, source) keep the existing match. Status, including
     * LOW_MATCH, is never changed here.
     */
    @Transactional
    public Job updateJobForCurrentUser(Long jobId, JobRequest request) {
        User owner = currentUserProvider.getCurrentUser();
        Job job = getOwnedJob(jobId, owner.getId());
        validateSalaryRange(request);

        String newCompanyKey = normalizeKeyPart(request.companyName());
        String newTitleKey = normalizeKeyPart(request.title());
        if (!newCompanyKey.equals(job.getCompanyKey()) || !newTitleKey.equals(job.getTitleKey())) {
            jobRepository.findByUserIdAndCompanyKeyAndTitleKey(owner.getId(), newCompanyKey, newTitleKey)
                    .filter(other -> !other.getId().equals(job.getId()))
                    .ifPresent(other -> {
                        throw new DuplicateJobException("Another job with this company and title already exists.");
                    });
        }

        String aiInputBefore = aiInput(job);
        Company previousCompany = job.getCompany();

        job.setTitle(collapseWhitespace(request.title()));
        job.setCompany(StringUtils.hasText(request.companyName())
                ? findOrCreateCompany(owner, collapseWhitespace(request.companyName()))
                : null);
        job.setLocation(blankToNull(request.location()));
        job.setWorkModel(blankToNull(request.workModel()));
        job.setUrl(blankToNull(request.url()));
        job.setNormalizedUrl(normalizeUrl(request.url()));
        job.setCompanyKey(newCompanyKey);
        job.setTitleKey(newTitleKey);
        job.setSalaryMin(request.salaryMin());
        job.setSalaryMax(request.salaryMax());
        job.setCurrency(blankToNull(request.currency()));
        // Metadata only: not part of aiInput(), so editing it never invalidates the match.
        job.setSource(blankToNull(request.source()));
        if (StringUtils.hasText(request.description())) {
            job.setDescription(request.description());
            job.setDescriptionDiscarded(false);
        } else {
            job.setDescription(null);
        }

        Job saved = jobRepository.saveAndFlush(job);
        if (!aiInputBefore.equals(aiInput(saved))) {
            jobMatchRepository.deleteByJobIdAndUserId(saved.getId(), owner.getId());
        }
        if (previousCompany != null && previousCompany != saved.getCompany()) {
            companyRepository.deleteIfUnreferenced(previousCompany.getId(), owner.getId());
        }
        return saved;
    }

    /** The job fields that make up the AI evaluation input (AiMatchRequest); also fingerprinted. */
    public static String aiInput(Job job) {
        return String.join("\u0000",
                Objects.toString(job.getTitle(), ""),
                job.getCompany() != null ? job.getCompany().getName() : "",
                Objects.toString(job.getLocation(), ""),
                Objects.toString(job.getWorkModel(), ""),
                Objects.toString(job.getDescription(), ""));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * Deduplication entry point for jobs added manually or through the MCP
     * integration, always within one owner's jobs. A job already exists when it
     * matches, in order, on source+externalId or on the canonical business key:
     * normalized company + normalized title. Returns the owner's existing Job
     * untouched in that case, so re-adding never creates a duplicate; another
     * user's jobs are never considered. uq_jobs_user_company_title is the final
     * guard against concurrent inserts; callers should retry on
     * DataIntegrityViolationException.
     */
    @Transactional
    public Job findOrCreateJob(User owner, JobRequest request, String source, String externalId) {
        validateSalaryRange(request);

        if (StringUtils.hasText(source) && StringUtils.hasText(externalId)) {
            Optional<Job> bySourceAndExternalId =
                    jobRepository.findByUserIdAndSourceAndExternalId(owner.getId(), source, externalId);
            if (bySourceAndExternalId.isPresent()) {
                return restoreDiscardedDescription(bySourceAndExternalId.get(), request);
            }
        }

        Optional<Job> byCompanyAndTitle = jobRepository.findByUserIdAndCompanyKeyAndTitleKey(
                owner.getId(), normalizeKeyPart(request.companyName()), normalizeKeyPart(request.title()));
        if (byCompanyAndTitle.isPresent()) {
            return restoreDiscardedDescription(byCompanyAndTitle.get(), request);
        }

        Job job = new Job(owner, request.title());
        applyRequest(job, request);
        job.setSource(blankToNull(source));
        job.setExternalId(externalId);
        return jobRepository.saveAndFlush(job);
    }

    /**
     * Permanently removes one of the user's jobs (its Application, stages,
     * history and match must already be gone, see ApplicationService), and
     * its company when no other job of the same user uses it any more.
     */
    @Transactional
    public void deleteJob(Job job) {
        Company company = job.getCompany();
        jobRepository.delete(job);
        jobRepository.flush();
        if (company != null) {
            companyRepository.deleteIfUnreferenced(company.getId(), job.getUser().getId());
        }
    }

    /**
     * Drops the stored description once a low AI match made it irrelevant.
     * Identity/dedup fields (company, title, keys, source, URL) are untouched.
     * Flushes so the audited updatedAt is current for the caller.
     */
    @Transactional
    public void discardDescription(Job job) {
        job.setDescription(null);
        job.setDescriptionDiscarded(true);
        jobRepository.saveAndFlush(job);
    }

    // Re-adding a deduplicated job that carries a description is the only way
    // to "refresh" a job whose description was discarded; anything else about
    // the existing job stays untouched.
    private Job restoreDiscardedDescription(Job existing, JobRequest request) {
        if (existing.isDescriptionDiscarded() && StringUtils.hasText(request.description())) {
            existing.setDescription(request.description());
            existing.setDescriptionDiscarded(false);
            return jobRepository.saveAndFlush(existing);
        }
        return existing;
    }

    /** One of the current user's jobs; any other id (missing or another user's) is reported as not found. */
    public Job getJobForCurrentUser(Long id) {
        return getOwnedJob(id, currentUserProvider.getCurrentUser().getId());
    }

    private Job getOwnedJob(Long id, Long userId) {
        return jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", id));
    }

    private void applyRequest(Job job, JobRequest request) {
        job.setDescription(request.description());
        job.setLocation(request.location());
        job.setWorkModel(request.workModel());
        job.setUrl(request.url());
        job.setSalaryMin(request.salaryMin());
        job.setSalaryMax(request.salaryMax());
        job.setCurrency(request.currency());
        job.setNormalizedUrl(normalizeUrl(request.url()));
        job.setCompanyKey(normalizeKeyPart(request.companyName()));
        job.setTitleKey(normalizeKeyPart(request.title()));

        if (StringUtils.hasText(request.companyName())) {
            job.setCompany(findOrCreateCompany(job.getUser(), collapseWhitespace(request.companyName())));
        }
    }

    private void validateSalaryRange(JobRequest request) {
        if (request.salaryMin() != null && request.salaryMax() != null
                && request.salaryMin() > request.salaryMax()) {
            throw new InvalidRequestException("salaryMin must not be greater than salaryMax");
        }
    }

    // The owner's company with this normalized name, created on first use.
    // Another user's company of the same name is a different, unrelated row.
    private Company findOrCreateCompany(User owner, String name) {
        String nameKey = normalizeKeyPart(name);
        return companyRepository.findByUserIdAndNameKey(owner.getId(), nameKey)
                .orElseGet(() -> companyRepository.save(new Company(owner, name, nameKey)));
    }

    /** Normalized form stored in {@code jobs.normalized_url} (lowercase, no scheme/www/query/trailing slash). */
    public static String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceFirst("^https?://(www\\.)?", "");
        normalized = normalized.replaceFirst("[?#].*$", "");
        normalized = normalized.replaceFirst("/+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    /** Collapse whitespace runs, trim, lowercase (locale-independent). No fuzzy matching. */
    public static String normalizeKeyPart(String value) {
        return collapseWhitespace(value).toLowerCase(Locale.ROOT);
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
