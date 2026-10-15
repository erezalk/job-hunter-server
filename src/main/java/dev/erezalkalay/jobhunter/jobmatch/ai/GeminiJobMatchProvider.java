package dev.erezalkalay.jobhunter.jobmatch.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Google Gemini {@code generateContent} REST API directly (no SDK
 * dependency — see PROJECT_STATUS.md). Uses {@code generationConfig}'s
 * {@code responseMimeType}/{@code responseSchema} to request structured JSON;
 * {@link AiResponseParser} still validates it defensively regardless.
 *
 * <p>Disabled under the {@code test} profile — see
 * {@code dev.erezalkalay.jobhunter.jobmatch.ai.FakeGeminiProvider} in tests.
 */
@Component
@Profile("!test")
public class GeminiJobMatchProvider implements AiJobMatchProvider {

    private static final String API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";

    private static final Map<String, Object> RESULT_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "fitScore", Map.of("type", "INTEGER"),
                    "matchingSkills", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                    "missingSkills", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                    "concerns", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
            ),
            "required", List.of("fitScore", "matchingSkills", "missingSkills", "concerns")
    );

    private final RestClient restClient;
    private final JobMatchPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final AiCallExecutor callExecutor;

    @Autowired
    public GeminiJobMatchProvider(
            AiRestClientFactory restClientFactory,
            JobMatchPromptBuilder promptBuilder,
            AiResponseParser responseParser,
            AiCallExecutor callExecutor
    ) {
        this(restClientFactory.builder().baseUrl(API_BASE_URL).build(), promptBuilder, responseParser, callExecutor);
    }

    GeminiJobMatchProvider(
            RestClient restClient,
            JobMatchPromptBuilder promptBuilder,
            AiResponseParser responseParser,
            AiCallExecutor callExecutor
    ) {
        this.restClient = restClient;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.callExecutor = callExecutor;
    }

    @Override
    public ProviderId id() {
        return ProviderId.GEMINI;
    }

    @Override
    public AiMatchResult evaluate(AiMatchRequest request, String apiKey, String model) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", promptBuilder.systemPrompt()))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", promptBuilder.userPrompt(request))))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", RESULT_SCHEMA
                )
        );

        GenerateContentResponse response = call(model, body, apiKey);
        String text = response.firstText();
        if (text == null) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }
        return responseParser.parse(text);
    }

    /**
     * Validates the key and model access with {@code GET models/{model}}: Google
     * rejects a missing/invalid key (403/400 API_KEY_INVALID), answers 404 for a
     * model this key cannot see, and lists the model's supported methods — all
     * without generating a single token. One request, never retried.
     */
    @Override
    public ConnectionTestResult testConnection(String apiKey, String model) {
        try {
            ModelInfo info = callExecutor.executeOnce("Gemini", model, () -> restClient.get()
                    .uri("/{model}", model)
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(ModelInfo.class));
            if (info == null || info.supportedGenerationMethods() == null
                    || !info.supportedGenerationMethods().contains("generateContent")) {
                throw new AiProviderException(AiErrorCode.AI_MODEL_UNAVAILABLE);
            }
            return ConnectionTestResult.ok();
        } catch (AiProviderException e) {
            return ConnectionTestResult.failure(e);
        }
    }

    private GenerateContentResponse call(String model, Map<String, Object> body, String apiKey) {
        return callExecutor.execute("Gemini", model, () -> restClient.post()
                .uri("/{model}:generateContent", model)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", apiKey)
                .body(body)
                .retrieve()
                .body(GenerateContentResponse.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerateContentResponse(List<Candidate> candidates) {
        String firstText() {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Content content = candidates.get(0).content();
            if (content == null || content.parts() == null) {
                return null;
            }
            return content.parts().stream()
                    .map(Part::text)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelInfo(List<String> supportedGenerationMethods) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Candidate(Content content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Part(String text) {
    }
}
