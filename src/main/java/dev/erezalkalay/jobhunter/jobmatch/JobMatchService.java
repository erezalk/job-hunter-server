package dev.erezalkalay.jobhunter.jobmatch;

import dev.erezalkalay.jobhunter.application.ApplicationService;
import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.cv.Cv;
import dev.erezalkalay.jobhunter.cv.CvRepository;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.job.JobService;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiJobMatchProviderRegistry;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchRequest;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.ProviderId;
import dev.erezalkalay.jobhunter.settings.Settings;
import dev.erezalkalay.jobhunter.settings.SettingsService;
import dev.erezalkalay.jobhunter.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Orchestrates AI job-fit evaluation: resolves the current user's active CV
 * and configured AI provider/model, decides (via a fingerprint) whether an
 * existing {@link JobMatch} is still reusable, calls the provider only when
 * it isn't, and persists the (validated) result.
 *
 * <p>An evaluation runs in three steps so no database transaction (or pooled
 * connection) is held while waiting for the provider: a short transaction
 * that resolves and checks the inputs, the provider call outside any
 * transaction, and a short transaction that re-checks the inputs and writes
 * the result.
 */
@Service
@Transactional(readOnly = true)
public class JobMatchService {

    private static final String PROMPT_VERSION = "v1";

    private final JobMatchRepository jobMatchRepository;
    private final JobService jobService;
    private final CvRepository cvRepository;
    private final ApplicationService applicationService;
    private final SettingsService settingsService;
    private final AiJobMatchProviderRegistry providerRegistry;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate transactionTemplate;

    public JobMatchService(
            JobMatchRepository jobMatchRepository,
            JobService jobService,
            CvRepository cvRepository,
            ApplicationService applicationService,
            SettingsService settingsService,
            AiJobMatchProviderRegistry providerRegistry,
            CurrentUserProvider currentUserProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.jobMatchRepository = jobMatchRepository;
        this.jobService = jobService;
        this.cvRepository = cvRepository;
        this.applicationService = applicationService;
        this.settingsService = settingsService;
        this.providerRegistry = providerRegistry;
        this.currentUserProvider = currentUserProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** The match of one of the current user's jobs; another user's job id is not found, like a missing one. */
    public Optional<JobMatch> getForCurrentUser(Long jobId) {
        Job job = jobService.getJobForCurrentUser(jobId);
        return jobMatchRepository.findByJobIdAndUserId(job.getId(), job.getUser().getId());
    }

    /**
     * Evaluates one of the current user's jobs (404 for any other job, before
     * anything else happens). Everything involved — job, CV, settings, API key,
     * stored match, Application — is the current user's; the reuse fingerprint
     * contains the job and CV ids, so it can never match another user's inputs.
     * Reuses the stored match when its inputs are unchanged and {@code force}
     * is false; otherwise calls the configured provider and stores the result,
     * applying the LOW_MATCH threshold rule to this job's own ticket only.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JobMatch evaluateForCurrentUser(Long jobId, boolean force) {
        Prepared prepared = transactionTemplate.execute(status -> prepare(jobId, force));
        if (prepared.cached() != null) {
            return prepared.cached();
        }
        // Deliberately outside any transaction: this can take tens of seconds.
        AiMatchResult result = providerRegistry.get(prepared.providerId())
                .evaluate(prepared.request(), prepared.apiKey(), prepared.model());
        return transactionTemplate.execute(status -> store(jobId, prepared.fingerprint(), result));
    }

    /**
     * Persists an evaluation that an external AI already calculated (MCP
     * add_job) as an ordinary JobMatch of the job's owner, attributed to
     * {@link JobMatch#PROVIDER_EXTERNAL_AI}. No AI provider is called and no
     * threshold/LOW_MATCH rule is applied: the score is informational, the
     * Application keeps its current status. The fingerprint is computed exactly
     * like an internal evaluation's (with the owner's active CV), so editing the
     * job's AI input or replacing the CV makes it stale the same way. An existing
     * match is never overwritten (returns empty). Joins the caller's transaction
     * so job, application and match commit together.
     */
    @Transactional
    public Optional<JobMatch> importExternalMatch(Job job, ImportedMatch imported) {
        Long ownerId = job.getUser().getId();
        if (jobMatchRepository.findByJobIdAndUserId(job.getId(), ownerId).isPresent()) {
            return Optional.empty();
        }
        Cv cv = cvRepository.findByUserIdAndPrimaryTrue(ownerId).orElse(null);
        String model = imported.evaluationModel();

        JobMatch match = new JobMatch(job);
        match.setCv(cv);
        match.setMatchScore(BigDecimal.valueOf(imported.fitScore()));
        match.setProvider(JobMatch.PROVIDER_EXTERNAL_AI);
        match.setModel(model);
        match.setMatchingSkills(imported.matchingSkills());
        match.setMissingSkills(imported.missingSkills());
        match.setConcerns(imported.concerns());
        match.setFingerprint(computeFingerprint(cv != null ? cv.getId() : null, job,
                JobMatch.PROVIDER_EXTERNAL_AI, model == null ? "" : model));
        match.setEvaluatedAt(Instant.now());
        return Optional.of(jobMatchRepository.save(match));
    }

    /** What the provider call needs, carried between transactions as plain values (no managed entities). */
    private record Prepared(
            JobMatch cached, ProviderId providerId, String model, String apiKey, String fingerprint, AiMatchRequest request) {

        static Prepared cached(JobMatch match) {
            return new Prepared(match, null, null, null, null, null);
        }
    }

    /** The evaluation inputs as they are right now, and their fingerprint. */
    private record Inputs(User user, Job job, Cv cv, Settings settings, ProviderId providerId, String fingerprint) {
    }

    private Inputs resolveInputs(Long jobId) {
        User user = currentUserProvider.getCurrentUser();
        Job job = jobService.getJobForCurrentUser(jobId);
        Cv cv = cvRepository.findByUserIdAndPrimaryTrue(user.getId())
                .orElseThrow(() -> new InvalidRequestException(
                        "Upload a CV before evaluating job fit"));

        Settings settings = settingsService.getForCurrentUser();
        if (!StringUtils.hasText(settings.getAiProvider()) || !StringUtils.hasText(settings.getAiModel())) {
            throw new InvalidRequestException(
                    "Configure an AI provider and model in Settings before evaluating job fit");
        }
        ProviderId providerId = ProviderId.valueOf(settings.getAiProvider());
        String fingerprint = computeFingerprint(cv.getId(), job, providerId.name(), settings.getAiModel());
        return new Inputs(user, job, cv, settings, providerId, fingerprint);
    }

    // Step 1 (short transaction): everything that can reject the request
    // without spending an AI call — ownership, CV, settings, key, cache, a
    // discarded description.
    private Prepared prepare(Long jobId, boolean force) {
        Inputs inputs = resolveInputs(jobId);
        String apiKey = settingsService.getDecryptedApiKey(inputs.providerId());
        if (!StringUtils.hasText(apiKey)) {
            throw new InvalidRequestException("Configure an API key in Settings before evaluating job fit");
        }

        Optional<JobMatch> existing = jobMatchRepository.findByJobIdAndUserId(jobId, inputs.user().getId());
        if (!force && existing.isPresent() && inputs.fingerprint().equals(existing.get().getFingerprint())) {
            return Prepared.cached(existing.get());
        }

        Job job = inputs.job();
        if (job.isDescriptionDiscarded()) {
            throw new InvalidRequestException("The job description was discarded after a low AI match, "
                    + "so this job cannot be evaluated again until a description is added (use Edit on the job)");
        }
        AiMatchRequest request = new AiMatchRequest(
                inputs.cv().getExtractedText(),
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getLocation(),
                job.getWorkModel(),
                job.getDescription()
        );
        return new Prepared(null, inputs.providerId(), inputs.settings().getAiModel(), apiKey, inputs.fingerprint(), request);
    }

    // Step 3 (short transaction): only runs after the provider returned a
    // valid result. Inputs are re-resolved; if the CV, provider/model or the
    // job's AI-relevant content changed during the call, the result no longer
    // describes the current inputs and is not stored. Status, cleanup and
    // match are written together.
    private JobMatch store(Long jobId, String evaluatedFingerprint, AiMatchResult result) {
        Inputs inputs = resolveInputs(jobId);
        if (!inputs.fingerprint().equals(evaluatedFingerprint)) {
            throw new EvaluationInputsChangedException();
        }
        User user = inputs.user();
        Job job = inputs.job();
        Cv cv = inputs.cv();
        Settings settings = inputs.settings();

        JobMatch match = jobMatchRepository.findByJobIdAndUserId(jobId, user.getId())
                .orElseGet(() -> new JobMatch(job));
        match.setCv(cv);
        match.setMatchScore(BigDecimal.valueOf(result.fitScore()));
        match.setProvider(inputs.providerId().name());
        match.setModel(settings.getAiModel());
        match.setMatchingSkills(result.matchingSkills());
        match.setMissingSkills(result.missingSkills());
        match.setConcerns(result.concerns());
        match.setFingerprint(evaluatedFingerprint);
        match.setEvaluatedAt(Instant.now());

        int minimumFitScore = settings.getMinimumFitScore();
        boolean meetsThreshold = result.fitScore() >= minimumFitScore;
        boolean lowMatch = applicationService.applyMatchOutcome(job, meetsThreshold, result.fitScore(), minimumFitScore);
        if (lowMatch) {
            jobService.discardDescription(job);
            match.setMatchingSkills(List.of());
            match.setMissingSkills(List.of());
            match.setConcerns(List.of());
            // The (now cleared) description is part of the fingerprinted AI
            // input; recompute so this evaluation still counts as cached.
            match.setFingerprint(computeFingerprint(cv.getId(), job, inputs.providerId().name(), settings.getAiModel()));
        }
        return jobMatchRepository.save(match);
    }

    private static String computeFingerprint(Long cvId, Job job, String provider, String model) {
        String raw = String.join("|",
                String.valueOf(cvId),
                String.valueOf(job.getId()),
                JobService.aiInput(job),
                provider,
                model,
                PROMPT_VERSION);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
