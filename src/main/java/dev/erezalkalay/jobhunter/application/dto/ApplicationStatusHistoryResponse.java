package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.ApplicationStatusHistory;
import java.time.Instant;

public record ApplicationStatusHistoryResponse(Long id, ApplicationStatusResponse status, Instant enteredAt, String notes) {

    public static ApplicationStatusHistoryResponse from(ApplicationStatusHistory entry) {
        return new ApplicationStatusHistoryResponse(
                entry.getId(),
                ApplicationStatusResponse.from(entry.getStatus()),
                entry.getEnteredAt(),
                entry.getNotes()
        );
    }
}
