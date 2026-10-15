package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.List;

/**
 * The structured result of an AI job-match evaluation, already validated —
 * {@code fitScore} is an integer from 0 to 100 and every list is non-null. See
 * {@link AiResponseParser} for how a raw provider response becomes this.
 */
public record AiMatchResult(
        int fitScore,
        List<String> matchingSkills,
        List<String> missingSkills,
        List<String> concerns
) {
}
