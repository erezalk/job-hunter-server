package dev.erezalkalay.jobhunter.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Drop position on the Board: the destination status plus at most one anchor
 * ticket of that column to land directly before/after. No anchor appends to
 * the end of the column. Anchors (not indexes) keep this correct when the
 * client only shows a filtered subset of the column.
 */
public record MoveRequest(@NotBlank String statusCode, Long beforeApplicationId, Long afterApplicationId) {
}
