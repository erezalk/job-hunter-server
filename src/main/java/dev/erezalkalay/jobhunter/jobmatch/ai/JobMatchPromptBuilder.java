package dev.erezalkalay.jobhunter.jobmatch.ai;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the system + user prompt text shared by every provider. Kept as one
 * place so the matching rules (what counts, what not to invent) can't drift
 * between the Anthropic and Gemini implementations.
 */
@Component
public class JobMatchPromptBuilder {

    public String systemPrompt() {
        return """
                You evaluate how well a job candidate fits a specific job posting, for a personal job-search \
                tracking tool. You will be given the candidate's CV text and a job posting.

                Base your evaluation ONLY on information actually present in the CV and the job posting. Do \
                not invent, assume, or infer candidate experience, skills, or qualifications that are not \
                stated in the CV. Do not invent job requirements that are not stated in the posting.

                Weigh: required technical skills, relevant professional experience, seniority level, stated \
                responsibilities, domain experience when genuinely relevant, education/certification \
                requirements when genuinely required, language requirements, and location/work-model \
                constraints when relevant.

                fitScore is an integer from 0 to 100 representing overall suitability for this specific \
                position. matchingSkills lists skills/qualifications from the CV that match \
                the posting. missingSkills lists skills/requirements the posting asks for that the CV does \
                not show. concerns lists other notable mismatches (seniority, location, language, etc.) — \
                leave it empty if there are none.

                Respond with only the structured JSON result.""";
    }

    public String userPrompt(AiMatchRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CANDIDATE CV ===\n").append(request.cvText()).append("\n\n");
        sb.append("=== JOB POSTING ===\n");
        sb.append("Title: ").append(request.jobTitle()).append('\n');
        if (StringUtils.hasText(request.companyName())) {
            sb.append("Company: ").append(request.companyName()).append('\n');
        }
        if (StringUtils.hasText(request.jobLocation())) {
            sb.append("Location: ").append(request.jobLocation()).append('\n');
        }
        if (StringUtils.hasText(request.jobWorkModel())) {
            sb.append("Work model: ").append(request.jobWorkModel()).append('\n');
        }
        sb.append("Description:\n").append(
                StringUtils.hasText(request.jobDescription()) ? request.jobDescription() : "(no description provided)");
        return sb.toString();
    }
}
