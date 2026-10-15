package dev.erezalkalay.jobhunter.jobmatch;

import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * An already-calculated job-fit result supplied by an external AI (the MCP
 * integration) instead of being computed by Job Hunter's configured provider.
 * It is only a carrier: it is persisted as an ordinary {@link JobMatch} by
 * {@link JobMatchService#importExternalMatch}.
 *
 * <p>{@code fitScore} is what makes an evaluation exist; the lists and the
 * model name are details of it and are rejected when supplied without one.
 */
public record ImportedMatch(
        int fitScore,
        List<String> matchingSkills,
        List<String> missingSkills,
        List<String> concerns,
        String evaluationModel
) {

    public static final int MODEL_MAX_LENGTH = 100;

    /**
     * Normalizes (trim, drop blanks, drop case-insensitive duplicates; wording
     * is otherwise untouched) and validates the raw request values. Returns
     * {@code null} when no evaluation was supplied at all; throws
     * {@link InvalidRequestException} for a score outside 0-100 or for details
     * given without a score. Never clamps or invents values.
     */
    public static ImportedMatch of(
            BigDecimal fitScore,
            List<String> matchingSkills,
            List<String> missingSkills,
            List<String> concerns,
            String evaluationModel
    ) {
        List<String> matching = normalize(matchingSkills);
        List<String> missing = normalize(missingSkills);
        List<String> concern = normalize(concerns);
        String model = StringUtils.hasText(evaluationModel) ? evaluationModel.trim() : null;

        if (fitScore == null) {
            if (!matching.isEmpty() || !missing.isEmpty() || !concern.isEmpty() || model != null) {
                throw new InvalidRequestException("fitScore is required when matchingSkills, missingSkills, "
                        + "concerns or evaluationModel are provided; omit all evaluation fields if the job was not evaluated");
            }
            return null;
        }
        if (fitScore.stripTrailingZeros().scale() > 0) {
            throw new InvalidRequestException("fitScore must be a whole number between 0 and 100");
        }
        if (fitScore.signum() < 0 || fitScore.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new InvalidRequestException("fitScore must be between 0 and 100");
        }
        if (model != null && model.length() > MODEL_MAX_LENGTH) {
            throw new InvalidRequestException("evaluationModel must be at most " + MODEL_MAX_LENGTH + " characters");
        }
        return new ImportedMatch(fitScore.intValueExact(), matching, missing, concern, model);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }
}
