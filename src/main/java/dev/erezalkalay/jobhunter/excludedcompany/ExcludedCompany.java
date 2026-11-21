package dev.erezalkalay.jobhunter.excludedcompany;

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
 * A company one user wants AI job discovery to skip. Owned by exactly one
 * {@link User}, unique per user ignoring case. Deliberately unrelated to the
 * Board's {@code Company}/{@code Job}: it never hides, changes or blocks a job.
 */
@Entity
@Table(name = "excluded_companies")
@EntityListeners(AuditingEntityListener.class)
public class ExcludedCompany {

    public static final int NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Trimmed, inner whitespace collapsed, never empty (see {@link ExcludedCompanyService#normalizeName}). */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExcludedCompany() {
    }

    public ExcludedCompany(User user, String name) {
        this.user = user;
        this.name = name;
    }

    void rename(String normalizedName) {
        this.name = normalizedName;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
