package dev.erezalkalay.jobhunter.jobmatch.ai;

/**
 * Everything an {@link AiJobMatchProvider} needs to score one CV against one
 * job. Deliberately provider-agnostic — providers turn this into their own
 * wire format.
 */
public record AiMatchRequest(
        String cvText,
        String jobTitle,
        String companyName,
        String jobLocation,
        String jobWorkModel,
        String jobDescription
) {
}
