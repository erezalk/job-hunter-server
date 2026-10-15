package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.Application;
import jakarta.validation.constraints.Size;

public record ApplicationNotesRequest(@Size(max = Application.NOTES_MAX_LENGTH) String notes) {
}
