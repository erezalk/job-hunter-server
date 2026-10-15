package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Looks up the {@link AiJobMatchProvider} for a {@link ProviderId}. This is
 * the one place that knows every implementation exists; everything else
 * (settings, matching) depends only on the interface.
 */
@Component
public class AiJobMatchProviderRegistry {

    private final Map<ProviderId, AiJobMatchProvider> providersById;

    public AiJobMatchProviderRegistry(List<AiJobMatchProvider> providers) {
        this.providersById = providers.stream()
                .collect(Collectors.toUnmodifiableMap(AiJobMatchProvider::id, Function.identity()));
    }

    public AiJobMatchProvider get(ProviderId id) {
        AiJobMatchProvider provider = providersById.get(id);
        if (provider == null) {
            throw new IllegalStateException("No AiJobMatchProvider registered for " + id);
        }
        return provider;
    }
}
