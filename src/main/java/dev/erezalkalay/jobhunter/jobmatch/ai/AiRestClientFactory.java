package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * One place to build the {@link RestClient} used for outbound AI provider
 * calls, with sensible connect/read timeouts — provider calls must never
 * hang the request thread indefinitely.
 */
@Component
public class AiRestClientFactory {

    private final JdkClientHttpRequestFactory requestFactory;

    public AiRestClientFactory(
            @Value("${app.ai.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms}") long readTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
        this.requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    }

    public RestClient.Builder builder() {
        return RestClient.builder().requestFactory(requestFactory);
    }
}
