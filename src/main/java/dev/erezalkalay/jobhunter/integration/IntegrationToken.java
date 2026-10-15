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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Digest of a user's personal MCP token. The raw token is never stored. */
@Entity
@Table(name = "integration_tokens")
@EntityListeners(AuditingEntityListener.class)
public class IntegrationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_suffix", nullable = false, updatable = false, length = 8)
    private String tokenSuffix;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IntegrationToken() {
    }

    public IntegrationToken(User user, String tokenHash, String tokenSuffix) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.tokenSuffix = tokenSuffix;
    }

    public User getUser() {
        return user;
    }

    public String getTokenSuffix() {
        return tokenSuffix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
