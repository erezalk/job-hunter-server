package dev.erezalkalay.jobhunter.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class SettingsIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        HttpResponse<String> login = client.login("test@jobhunter.local", "test-password-123");
        assertThat(login.statusCode()).isEqualTo(200);
    }

    @Test
    void defaultSettingsHaveAllSixBoardColumnsVisible() throws Exception {
        HttpResponse<String> response = client.get("/api/settings");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.visibleBoardStatuses"))
                .containsExactlyInAnyOrder("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER", "REJECTED", "ARCHIVED");
    }

    @Test
    void visibleBoardColumnsCanBeChangedAndPersist() throws Exception {
        String update = """
                {"theme":"SYSTEM","timezone":"UTC","visibleBoardStatuses":["NEW","CV_SENT","INTERVIEW_PROCESS","OFFER"],"minimumFitScore":70}
                """;
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(put.body(), "$.visibleBoardStatuses"))
                .containsExactlyInAnyOrder("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER");

        HttpResponse<String> reload = client.get("/api/settings");
        assertThat(JsonPath.<List<String>>read(reload.body(), "$.visibleBoardStatuses"))
                .containsExactlyInAnyOrder("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER");
    }

    @Test
    void emptyVisibleBoardColumnsSelectionIsRejected() throws Exception {
        String update = """
                {"theme":"SYSTEM","timezone":"UTC","visibleBoardStatuses":[],"minimumFitScore":70}
                """;
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(400);
    }

    @Test
    void changingVisibleBoardColumnsDoesNotModifyApplicationStatuses() throws Exception {
        String jobJson = "{\"title\":\"Settings Test Role\",\"companyName\":\"Settings Co\"}";
        HttpResponse<String> createApplication = client.postJson("/api/board/jobs", jobJson);
        Integer applicationId = JsonPath.read(createApplication.body(), "$.id");
        assertThat(JsonPath.<String>read(createApplication.body(), "$.status.code")).isEqualTo("NEW");

        String update = """
                {"theme":"SYSTEM","timezone":"UTC","visibleBoardStatuses":["NEW"],"minimumFitScore":70}
                """;
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(200);

        HttpResponse<String> application = client.get("/api/applications/" + applicationId);
        assertThat(JsonPath.<String>read(application.body(), "$.status.code")).isEqualTo("NEW");

        HttpResponse<String> history = client.get("/api/applications/" + applicationId + "/status-history");
        assertThat(JsonPath.<List<String>>read(history.body(), "$[*].status.code")).containsExactly("NEW");
    }

    @Test
    void minimumFitScoreOutOfRangeIsRejected() throws Exception {
        String update = """
                {"theme":"SYSTEM","timezone":"UTC","visibleBoardStatuses":["NEW"],"minimumFitScore":150}
                """;
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(400);
    }

    @Test
    void aiProviderRequiresAModel() throws Exception {
        String update = """
                {"theme":"SYSTEM","timezone":"UTC","visibleBoardStatuses":["NEW"],\
                "minimumFitScore":70,"aiProvider":"ANTHROPIC","aiModel":""}
                """;
        HttpResponse<String> put = client.putJson("/api/settings", update);
        assertThat(put.statusCode()).isEqualTo(400);
    }

    @Test
    void apiKeyIsNeverReturnedByGetSettings() throws Exception {
        String setKey = "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"sk-super-secret-value\"}";
        HttpResponse<String> putKey = client.putJson("/api/settings/ai/api-key", setKey);
        assertThat(putKey.statusCode()).isEqualTo(200);
        assertThat(putKey.body()).doesNotContain("sk-super-secret-value");
        assertThat(JsonPath.<Boolean>read(putKey.body(), "$.anthropicApiKeyConfigured")).isTrue();

        HttpResponse<String> reload = client.get("/api/settings");
        assertThat(reload.body()).doesNotContain("sk-super-secret-value").doesNotContain("ApiKey\":\"sk-");
        assertThat(JsonPath.<Boolean>read(reload.body(), "$.anthropicApiKeyConfigured")).isTrue();
    }

    @Test
    void anthropicAndGeminiApiKeysAreStoredSeparately() throws Exception {
        client.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"anthropic-secret\"}");
        client.putJson("/api/settings/ai/api-key", "{\"provider\":\"GEMINI\",\"apiKey\":\"gemini-secret\"}");

        HttpResponse<String> settings = client.get("/api/settings");
        assertThat(JsonPath.<Boolean>read(settings.body(), "$.anthropicApiKeyConfigured")).isTrue();
        assertThat(JsonPath.<Boolean>read(settings.body(), "$.geminiApiKeyConfigured")).isTrue();

        HttpResponse<String> removeAnthropic = client.deleteRequest("/api/settings/ai/api-key?provider=ANTHROPIC");
        assertThat(removeAnthropic.statusCode()).isEqualTo(200);

        HttpResponse<String> afterRemoval = client.get("/api/settings");
        assertThat(JsonPath.<Boolean>read(afterRemoval.body(), "$.anthropicApiKeyConfigured")).isFalse();
        // Removing Anthropic's key must not touch Gemini's.
        assertThat(JsonPath.<Boolean>read(afterRemoval.body(), "$.geminiApiKeyConfigured")).isTrue();
    }
}
