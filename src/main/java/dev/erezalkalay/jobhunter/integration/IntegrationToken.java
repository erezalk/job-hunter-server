package dev.erezalkalay.jobhunter.integration;

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
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One of a user's integration tokens (MCP clients such as Claude Desktop or
 * Codex). Only the digest of the secret is stored, never the raw token.
 *
 * <p>The id, owner, name and creation time are stable; regenerating replaces
 * only the secret (digest + suffix). {@link DynamicUpdate} keeps a rename from
 * ever writing the digest back, in addition to the row lock every change takes.
 */
@Entity
@Table(name = "integration_tokens")
@EntityListeners(AuditingEntityListener.class)
@DynamicUpdate
public class IntegrationToken {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "token_suffix", nullable = false, length = 8)
    private String tokenSuffix;

    /** Null for an unnamed token; never blank (see {@link IntegrationTokenService#normalizeName}). */
    @Column(name = "name", length = NAME_MAX_LENGTH)
    private String name;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "regenerated_at")
    private Instant regeneratedAt;

    protected IntegrationToken() {
    }

    public IntegrationToken(User user, String name, String tokenHash, String tokenSuffix) {
        this.user = user;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenSuffix = tokenSuffix;
    }

    /** Replaces the secret; the previous digest no longer matches anything once this commits. */
    void replaceSecret(String newHash, String newSuffix, Instant now) {
        this.tokenHash = newHash;
        this.tokenSuffix = newSuffix;
        this.regeneratedAt = now;
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

    public String getTokenSuffix() {
        return tokenSuffix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRegeneratedAt() {
        return regeneratedAt;
    }
}
