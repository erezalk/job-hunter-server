package dev.erezalkalay.jobhunter.application.dto;

import dev.erezalkalay.jobhunter.application.Application;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Stage create/update body. Every field except {@code scheduledEndAt} is a full
 * replace on update (an omitted field is cleared, as before). {@code scheduledEndAt}
 * is three-state so a client that does not know it cannot erase an existing end:
 * omitted (Java null) keeps the stored value, an explicit JSON {@code null}
 * (empty Optional) clears it, a value sets it.
 */
public record ApplicationStageRequest(
        @NotBlank @Size(max = 255) String name,
        Instant scheduledAt,
        @JsonDeserialize(using = OmittableInstantDeserializer.class) Optional<Instant> scheduledEndAt,
        @Size(max = 500) String interviewerNames,
        @Size(max = Application.NOTES_MAX_LENGTH) String notes,
        @Size(max = 255) String outcome
) {
}
