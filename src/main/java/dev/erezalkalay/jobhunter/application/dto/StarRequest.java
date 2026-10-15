package dev.erezalkalay.jobhunter.application.dto;

import jakarta.validation.constraints.NotNull;

public record StarRequest(@NotNull Boolean starred) {
}
