package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.ApplicationStatus;

public record ApplicationStatusResponse(String code, String label, Integer sortOrder, boolean terminal) {

    public static ApplicationStatusResponse from(ApplicationStatus status) {
        return new ApplicationStatusResponse(status.getCode(), status.getLabel(), status.getSortOrder(), status.isTerminal());
    }
}
