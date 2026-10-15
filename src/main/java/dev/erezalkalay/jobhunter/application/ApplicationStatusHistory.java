package dev.erezalkalay.jobhunter.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Audit trail entry recording that an {@link Application} entered a given
 * board status at a point in time. Independent from Application#status,
 * which always reflects the current value, and from {@link ApplicationStage}
 * which records real recruitment events, not status movement.
 */
@Entity
@Table(name = "application_status_history")
public class ApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_code", nullable = false)
    private ApplicationStatus status;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    private String notes;

    protected ApplicationStatusHistory() {
    }

    public ApplicationStatusHistory(Application application, ApplicationStatus status, String notes) {
        this.application = application;
        this.status = status;
        this.notes = notes;
        this.enteredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }

    public String getNotes() {
        return notes;
    }
}
