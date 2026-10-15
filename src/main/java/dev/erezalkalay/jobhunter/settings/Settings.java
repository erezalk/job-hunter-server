package dev.erezalkalay.jobhunter.settings;

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
import java.util.Arrays;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "settings")
@EntityListeners(AuditingEntityListener.class)
public class Settings {

    // Board columns visible by default for new Settings rows; kept in sync with
    // the settings.visible_board_statuses column default. LOW_MATCH is
    // deliberately NOT in it: that column is opt-in.
    public static final List<String> DEFAULT_VISIBLE_BOARD_STATUS_CODES =
            List.of("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER", "REJECTED", "ARCHIVED");

    public static final int DEFAULT_MINIMUM_FIT_SCORE = 70;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private String theme = "SYSTEM";

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(name = "visible_board_statuses", nullable = false)
    private String visibleBoardStatuses = String.join(",", DEFAULT_VISIBLE_BOARD_STATUS_CODES);

    @Column(name = "minimum_fit_score", nullable = false)
    private int minimumFitScore = DEFAULT_MINIMUM_FIT_SCORE;

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    // AES-GCM ciphertext (see EncryptionService) — never the plaintext key,
    // never returned from any API response.
    @Column(name = "anthropic_api_key_encrypted")
    private String anthropicApiKeyEncrypted;

    @Column(name = "gemini_api_key_encrypted")
    private String geminiApiKeyEncrypted;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Settings() {
    }

    public Settings(User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<String> getVisibleBoardStatusCodes() {
        return Arrays.asList(visibleBoardStatuses.split(","));
    }

    public void setVisibleBoardStatusCodes(List<String> codes) {
        this.visibleBoardStatuses = String.join(",", codes);
    }

    public int getMinimumFitScore() {
        return minimumFitScore;
    }

    public void setMinimumFitScore(int minimumFitScore) {
        this.minimumFitScore = minimumFitScore;
    }

    public String getAiProvider() {
        return aiProvider;
    }

    public void setAiProvider(String aiProvider) {
        this.aiProvider = aiProvider;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getAnthropicApiKeyEncrypted() {
        return anthropicApiKeyEncrypted;
    }

    public void setAnthropicApiKeyEncrypted(String anthropicApiKeyEncrypted) {
        this.anthropicApiKeyEncrypted = anthropicApiKeyEncrypted;
    }

    public String getGeminiApiKeyEncrypted() {
        return geminiApiKeyEncrypted;
    }

    public void setGeminiApiKeyEncrypted(String geminiApiKeyEncrypted) {
        this.geminiApiKeyEncrypted = geminiApiKeyEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
