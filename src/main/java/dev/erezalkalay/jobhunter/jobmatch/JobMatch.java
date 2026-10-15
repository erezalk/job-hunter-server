package dev.erezalkalay.jobhunter.jobmatch;

import dev.erezalkalay.jobhunter.cv.Cv;
import dev.erezalkalay.jobhunter.job.Job;
import dev.erezalkalay.jobhunter.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The stored result of evaluating a {@link Job} against its owner's CV with an
 * AI provider. Owned by the job's {@link User} (always taken from the job, and
 * enforced by the composite foreign key); at most one row per job, so
 * re-evaluating overwrites it rather than creating a history.
 * {@code fingerprint} is what {@code JobMatchService} uses to decide whether a
 * stored row is still reusable (see the fingerprint in JobMatchService).
 */
@Entity
@Table(name = "job_matches")
public class JobMatch {

    /**
     * {@code provider} value of a match that was imported from an external AI
     * (MCP) rather than computed by a configured {@code ProviderId}. Deliberately
     * not a {@code ProviderId}: it can never be selected in Settings or invoked.
     */
    public static final String PROVIDER_EXTERNAL_AI = "EXTERNAL_AI";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cv_id")
    private Cv cv;

    @Column(name = "match_score", precision = 5, scale = 2)
    private BigDecimal matchScore;

    private String provider;

    private String model;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "matching_skills")
    private List<String> matchingSkills = List.of();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "missing_skills")
    private List<String> missingSkills = List.of();

    @Convert(converter = StringListJsonConverter.class)
    private List<String> concerns = List.of();

    private String fingerprint;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JobMatch() {
    }

    public JobMatch(Job job) {
        this.job = job;
        this.user = job.getUser();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public User getUser() {
        return user;
    }

    public Cv getCv() {
        return cv;
    }

    public void setCv(Cv cv) {
        this.cv = cv;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getMatchingSkills() {
        return matchingSkills;
    }

    public void setMatchingSkills(List<String> matchingSkills) {
        this.matchingSkills = matchingSkills;
    }

    public List<String> getMissingSkills() {
        return missingSkills;
    }

    public void setMissingSkills(List<String> missingSkills) {
        this.missingSkills = missingSkills;
    }

    public List<String> getConcerns() {
        return concerns;
    }

    public void setConcerns(List<String> concerns) {
        this.concerns = concerns;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
