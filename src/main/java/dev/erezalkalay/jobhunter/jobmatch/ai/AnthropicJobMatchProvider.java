package dev.erezalkalay.jobhunter.jobmatch.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Anthropic Messages API directly (no SDK dependency — see
 * PROJECT_STATUS.md for why). Uses {@code output_config.format} structured
 * outputs so the response is guaranteed-parseable JSON; {@link AiResponseParser}
 * still validates it defensively regardless.
 *
 * <p>Disabled under the {@code test} profile — see
 * {@code dev.erezalkalay.jobhunter.jobmatch.ai.FakeAnthropicProvider} in
 * tests, which replaces it so automated tests never make a real, billed API
 * call.
 */
@Component
@Profile("!test")
public class AnthropicJobMatchProvider implements AiJobMatchProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final long MAX_TOKENS = 2048;

    private static final Map<String, Object> RESULT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fitScore", Map.of("type", "integer"),
                    "matchingSkills", Map.of("type", "array", "items", Map.of("type", "string")),
                    "missingSkills", Map.of("type", "array", "items", Map.of("type", "string")),
                    "concerns", Map.of("type", "array", "items", Map.of("type", "string"))
            ),
            "required", List.of("fitScore", "matchingSkills", "missingSkills", "concerns"),
            "additionalProperties", false
    );

    private final RestClient restClient;
    private final JobMatchPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final AiCallExecutor callExecutor;

    public AnthropicJobMatchProvider(
            AiRestClientFactory restClientFactory,
            JobMatchPromptBuilder promptBuilder,
            AiResponseParser responseParser,
            AiCallExecutor callExecutor
    ) {
        this.restClient = restClientFactory.builder().baseUrl(API_URL).build();
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.callExecutor = callExecutor;
    }

    @Override
    public ProviderId id() {
        return ProviderId.ANTHROPIC;
    }

    @Override
    public AiMatchResult evaluate(AiMatchRequest request, String apiKey, String model) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                "system", promptBuilder.systemPrompt(),
                "messages", List.of(Map.of("role", "user", "content", promptBuilder.userPrompt(request))),
                "output_config", Map.of("format", Map.of(
                        "type", "json_schema",
                        "schema", RESULT_SCHEMA
                ))
        );

        MessageResponse response = call(model, body, apiKey);
        String text = response.firstText();
        if (text == null) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_RESPONSE);
        }
        return responseParser.parse(text);
    }

    @Override
    public ConnectionTestResult testConnection(String apiKey, String model) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 8,
                    "messages", List.of(Map.of("role", "user", "content", "Reply with the single word OK."))
            );
            callExecutor.executeOnce("Anthropic", model, () -> send(body, apiKey));
            return ConnectionTestResult.ok();
        } catch (AiProviderException e) {
            return ConnectionTestResult.failure(e);
        }
    }

    private MessageResponse call(String model, Map<String, Object> body, String apiKey) {
        return callExecutor.execute("Anthropic", model, () -> send(body, apiKey));
    }

    private MessageResponse send(Map<String, Object> body, String apiKey) {
        return restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .body(body)
                .retrieve()
                .body(MessageResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageResponse(List<ContentBlock> content) {
        String firstText() {
            if (content == null) {
                return null;
            }
            return content.stream()
                    .filter(b -> "text".equals(b.type()) && b.text() != null)
                    .map(ContentBlock::text)
                    .findFirst()
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentBlock(String type, String text) {
    }
}
