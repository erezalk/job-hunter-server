package dev.erezalkalay.jobhunter.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Reference/lookup row for an allowed application status. Adding a new
 * status is a plain row insert (a Flyway migration) rather than a schema
 * or enum change.
 */
@Entity
@Table(name = "application_statuses")
public class ApplicationStatus {

    @Id
    private String code;

    @Column(nullable = false)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "is_terminal", nullable = false)
    private boolean terminal;

    protected ApplicationStatus() {
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
