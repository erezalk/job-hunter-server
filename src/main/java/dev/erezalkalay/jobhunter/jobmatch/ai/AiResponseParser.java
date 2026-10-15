package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a provider's raw JSON text response into a validated
 * {@link AiMatchResult}. Never trusts the model: the score must be an
 * integer from 0 to 100 (anything else is a malformed-response failure, never
 * a defaulted or clamped score), the skill/concern lists default to empty when
 * missing or wrong-typed, and a response that isn't parseable JSON at all is
 * reported as a malformed-response failure rather than silently persisted.
 */
@Component
public class AiResponseParser {

    private final ObjectMapper objectMapper;

    public AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiMatchResult parse(String rawText) {
        String jsonText = extractJsonObject(rawText);
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonText);
        } catch (Exception e) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }

        int fitScore = readFitScore(root);
        List<String> matchingSkills = readStringList(root, "matchingSkills");
        List<String> missingSkills = readStringList(root, "missingSkills");
        List<String> concerns = readStringList(root, "concerns");

        return new AiMatchResult(fitScore, matchingSkills, missingSkills, concerns);
    }

    private List<String> readStringList(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = root.path(field);
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    // fitScore must be a JSON integer from 0 to 100, exactly as both providers'
    // response schemas declare it. Anything else (missing, null, a string, a
    // boolean, a fraction, out of range) is an invalid response: it is never
    // coerced, clamped or defaulted, because a made-up score (e.g. 0) would
    // drive the LOW_MATCH rule and discard the job description.
    private static int readFitScore(JsonNode root) {
        JsonNode score = root.get("fitScore");
        if (score == null || !score.isIntegralNumber() || !score.canConvertToInt()) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }
        int value = score.intValue();
        if (value < 0 || value > 100) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }
        return value;
    }

    /**
     * Providers are asked to return only JSON, but defensively extract the
     * first {@code {...}} block in case a provider wraps it in prose or a
     * markdown code fence.
     */
    private static String extractJsonObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            return text;
        }
        return text.substring(start, end + 1);
    }
}
