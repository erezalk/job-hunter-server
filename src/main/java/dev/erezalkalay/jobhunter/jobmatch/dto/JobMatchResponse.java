package dev.erezalkalay.jobhunter.jobmatch.dto;

import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import java.time.Instant;
import java.util.List;

public record JobMatchResponse(
        Integer fitScore,
        List<String> matchingSkills,
        List<String> missingSkills,
        List<String> concerns,
        String provider,
        String model,
        Instant evaluatedAt
) {

    public static JobMatchResponse from(JobMatch match) {
        return new JobMatchResponse(
                match.getMatchScore() != null ? match.getMatchScore().intValue() : null,
                match.getMatchingSkills(),
                match.getMissingSkills(),
                match.getConcerns(),
                match.getProvider(),
                match.getModel(),
                match.getEvaluatedAt()
        );
    }
}
