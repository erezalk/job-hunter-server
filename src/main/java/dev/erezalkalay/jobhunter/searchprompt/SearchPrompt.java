package dev.erezalkalay.jobhunter.searchprompt;

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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One user's saved job-search instructions (Settings → Job Search → Search Prompt), stored
 * verbatim as plain text. At most one per user; absent when none is configured.
 */
@Entity
@Table(name = "search_prompts")
@EntityListeners(AuditingEntityListener.class)
public class SearchPrompt {

    /** Upper bound of one request; the TEXT column itself has no limit. */
    public static final int PROMPT_MAX_LENGTH = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
    private User user;

    /** Exactly as the user entered it; never blank (a blank prompt is not stored). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SearchPrompt() {
    }

    SearchPrompt(User user, String prompt) {
        this.user = user;
        this.prompt = prompt;
    }

    void replaceText(String prompt) {
        this.prompt = prompt;
    }

    public Long getId() {
        return id;
    }

    public String getPrompt() {
        return prompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
