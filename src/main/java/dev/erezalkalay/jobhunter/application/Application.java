package dev.erezalkalay.jobhunter.application;

import dev.erezalkalay.jobhunter.job.Job;
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
 * An Application is the decision to pursue a {@link Job}. A Job can exist
 * for a long time before (or without ever) becoming an Application.
 */
@Entity
@Table(name = "applications")
@EntityListeners(AuditingEntityListener.class)
public class Application {

    /** Request limit for general/stage notes (the columns are TEXT); only stops accidental huge payloads. */
    public static final int NOTES_MAX_LENGTH = 50_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Eager: ApplicationResponse always renders job/user fields, and the DTO
    // mapping happens in the controller after the service's transaction has
    // closed (open-in-view is disabled).
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_code", nullable = false)
    private ApplicationStatus status;

    @Column(name = "applied_at")
    private Instant appliedAt;

    private String notes;

    // Per-user Board organization: star flag and manual order within the
    // (user, status) column — lower comes first, ties fall back to id DESC.
    @Column(nullable = false)
    private boolean starred;

    @Column(name = "board_position", nullable = false)
    private int boardPosition;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Application() {
    }

    public Application(Job job, User user, ApplicationStatus status) {
        this.job = job;
        this.user = user;
        this.status = status;
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

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isStarred() {
        return starred;
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }

    public int getBoardPosition() {
        return boardPosition;
    }

    public void setBoardPosition(int boardPosition) {
        this.boardPosition = boardPosition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
