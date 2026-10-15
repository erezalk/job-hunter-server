package dev.erezalkalay.jobhunter.job;

import dev.erezalkalay.jobhunter.company.Company;
import dev.erezalkalay.jobhunter.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A posting one user tracks. Owned by exactly one {@link User}: nothing about it
 * (fields, company, description, AI match, Board ticket) is visible to or
 * changeable by another user. Always has exactly one {@code Application}.
 */
@Entity
@Table(name = "jobs")
@EntityListeners(AuditingEntityListener.class)
public class Job {

    /** Assigned server-side to jobs created through the browser's Add Job. */
    public static final String SOURCE_MANUAL = "Manual";
    /** Assigned server-side to jobs created through the MCP integration. */
    public static final String SOURCE_EXTERNAL_AI = "External AI";
    public static final int SOURCE_MAX_LENGTH = 100;
    /**
     * Request limit for a description (the column is TEXT): far above any real
     * posting, it only stops accidental megabyte payloads and AI prompts.
     */
    public static final int DESCRIPTION_MAX_LENGTH = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    // Eager: JobResponse always renders the company name, and the DTO mapping
    // happens in the controller after the service's transaction has closed.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String title;

    private String description;

    // True once the description was deliberately dropped after a low AI match.
    // Distinguishes "discarded" from "never had a description".
    @Column(name = "description_discarded", nullable = false)
    private boolean descriptionDiscarded;

    private String location;

    @Column(name = "work_model")
    private String workModel;

    private String url;

    @Column(name = "normalized_url")
    private String normalizedUrl;

    // Canonical per-user uniqueness key: normalized company + normalized title
    // (see JobService.normalizeKeyPart); enforced by uq_jobs_user_company_title.
    @Column(name = "company_key", nullable = false)
    private String companyKey = "";

    @Column(name = "title_key", nullable = false)
    private String titleKey = "";

    @Column(name = "salary_min")
    private Integer salaryMin;

    @Column(name = "salary_max")
    private Integer salaryMax;

    private String currency;

    // Optional free-text label ("Manual", "External AI", or anything the user
    // types). Null for jobs that predate the field. Metadata only: it takes no
    // part in deduplication or in the AI-match fingerprint.
    private String source;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Job() {
    }

    public Job(User user, String title) {
        this.user = user;
        this.title = title;
        this.discoveredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isDescriptionDiscarded() {
        return descriptionDiscarded;
    }

    public void setDescriptionDiscarded(boolean descriptionDiscarded) {
        this.descriptionDiscarded = descriptionDiscarded;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getWorkModel() {
        return workModel;
    }

    public void setWorkModel(String workModel) {
        this.workModel = workModel;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public void setNormalizedUrl(String normalizedUrl) {
        this.normalizedUrl = normalizedUrl;
    }

    public String getCompanyKey() {
        return companyKey;
    }

    public void setCompanyKey(String companyKey) {
        this.companyKey = companyKey;
    }

    public String getTitleKey() {
        return titleKey;
    }

    public void setTitleKey(String titleKey) {
        this.titleKey = titleKey;
    }

    public Integer getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(Integer salaryMin) {
        this.salaryMin = salaryMin;
    }

    public Integer getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(Integer salaryMax) {
        this.salaryMax = salaryMax;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
