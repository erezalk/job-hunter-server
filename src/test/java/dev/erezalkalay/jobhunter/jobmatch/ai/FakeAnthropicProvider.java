package dev.erezalkalay.jobhunter.jobmatch.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test double for {@link AiJobMatchProvider} (Anthropic) — replaces the real
 * provider under the {@code test} profile so automated tests never make a
 * real, billed API call.
 */
@Component
@Profile("test")
public class FakeAnthropicProvider implements AiJobMatchProvider {

    private final FakeAiProviderState state;

    public FakeAnthropicProvider(FakeAiProviderState state) {
        this.state = state;
    }

    @Override
    public ProviderId id() {
        return ProviderId.ANTHROPIC;
    }

    @Override
    public AiMatchResult evaluate(AiMatchRequest request, String apiKey, String model) {
        requireValidKey(apiKey);
        return state.evaluate();
    }

    @Override
    public ConnectionTestResult testConnection(String apiKey, String model) {
        try {
            requireValidKey(apiKey);
            state.failIfScripted();
            return ConnectionTestResult.ok();
        } catch (AiProviderException e) {
            return ConnectionTestResult.failure(e);
        }
    }

    private void requireValidKey(String apiKey) {
        if (!FakeAiProviderState.VALID_API_KEY.equals(apiKey)) {
            throw new AiProviderException(AiErrorCode.AI_INVALID_API_KEY);
        }
    }
}
