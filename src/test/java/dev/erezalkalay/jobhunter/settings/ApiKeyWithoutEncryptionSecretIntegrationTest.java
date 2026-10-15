package dev.erezalkalay.jobhunter.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

// A server started without APP_ENCRYPTION_SECRET must answer "save API key"
// with an explained error, never an unexplained HTTP 500.
@TestPropertySource(properties = "app.encryption.master-secret=")
class ApiKeyWithoutEncryptionSecretIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Test
    void savingAnApiKeyWithoutAnEncryptionSecretIsAClearServiceErrorNotA500() throws Exception {
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);

        HttpResponse<String> response = client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"GEMINI\",\"apiKey\":\"never-stored-key\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("APP_ENCRYPTION_SECRET");
        assertThat(response.body()).doesNotContain("never-stored-key");
    }
}
