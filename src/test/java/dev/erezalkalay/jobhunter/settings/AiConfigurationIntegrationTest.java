package dev.erezalkalay.jobhunter.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class AiConfigurationIntegrationTest extends IntegrationTestBase {

    private static final String MY_EMAIL = "test@jobhunter.local";
    private static final String OTHER_EMAIL = "ai-other@jobhunter.local";
    private static final String OTHER_PASSWORD = "other-password-123";
    private static final String ALL_STATUSES = "\"NEW\",\"CV_SENT\",\"INTERVIEW_PROCESS\",\"OFFER\",\"REJECTED\",\"ARCHIVED\"";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login(MY_EMAIL, "test-password-123").statusCode()).isEqualTo(200);
    }

    @Test
    void apiKeysAreEncryptedAtRestNeverReturnedAndStoredPerProvider() throws Exception {
        HttpResponse<String> gemini = client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"GEMINI\",\"apiKey\":\"plain-gemini-key-123\"}");
        assertThat(gemini.statusCode()).isEqualTo(200);
        HttpResponse<String> anthropic = client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"plain-anthropic-key-456\"}");

        assertThat(gemini.body()).doesNotContain("plain-gemini-key-123");
        assertThat(anthropic.body()).doesNotContain("plain-anthropic-key-456").doesNotContain("plain-gemini-key-123");
        // Saving Anthropic's key did not destroy Gemini's.
        assertThat(JsonPath.<Boolean>read(anthropic.body(), "$.geminiApiKeyConfigured")).isTrue();
        assertThat(JsonPath.<Boolean>read(anthropic.body(), "$.anthropicApiKeyConfigured")).isTrue();

        String mine = "(SELECT id FROM users WHERE email = '" + MY_EMAIL + "')";
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = " + mine
                + " AND gemini_api_key_encrypted IS NOT NULL AND gemini_api_key_encrypted NOT LIKE '%plain-gemini-key-123%'"
                + " AND anthropic_api_key_encrypted IS NOT NULL AND anthropic_api_key_encrypted NOT LIKE '%plain-anthropic-key-456%'"))
                .isEqualTo(1);
    }

    @Test
    void removingAiConfigurationResetsOnlyTheCurrentUsersAiData() throws Exception {
        testData.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);

        // Mine: full AI config, non-AI settings, a CV, a job with a match.
        configure(client, "GEMINI", "gemini-3.8-flash", 90, "DARK");
        client.putJson("/api/settings/ai/api-key", "{\"provider\":\"GEMINI\",\"apiKey\":\"my-gemini-key\"}");
        client.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"my-anthropic-key\"}");
        assertThat(client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Experienced Java backend engineer with Spring Boot and PostgreSQL skills")).statusCode())
                .isEqualTo(201);
        HttpResponse<String> job = client.postJson("/api/board/jobs",
                "{\"title\":\"Remove AI Engineer\",\"companyName\":\"Remove AI Corp\"}");
        int jobId = JsonPath.read(job.body(), "$.jobId");
        int applicationId = JsonPath.read(job.body(), "$.id");
        testData.createJobMatch((long) jobId, 88);

        // Other user: their own config, and their own job (same company + title) with their own match.
        configure(other, "ANTHROPIC", "claude-sonnet-5", 55, "LIGHT");
        other.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"their-anthropic-key\"}");
        HttpResponse<String> theirJob = other.postJson("/api/board/jobs",
                "{\"title\":\"Remove AI Engineer\",\"companyName\":\"Remove AI Corp\"}");
        int theirJobId = JsonPath.read(theirJob.body(), "$.jobId");
        assertThat(theirJobId).isNotEqualTo(jobId);
        testData.createJobMatch((long) theirJobId, 55);

        assertThat(boardFitScore(client, applicationId)).isNotNull();

        assertThat(client.deleteRequest("/api/settings/ai").statusCode()).isEqualTo(204);

        HttpResponse<String> settings = client.get("/api/settings");
        assertThat((Object) JsonPath.read(settings.body(), "$.aiProvider")).isNull();
        assertThat((Object) JsonPath.read(settings.body(), "$.aiModel")).isNull();
        assertThat((Integer) JsonPath.read(settings.body(), "$.minimumFitScore")).isEqualTo(70);
        assertThat(JsonPath.<Boolean>read(settings.body(), "$.geminiApiKeyConfigured")).isFalse();
        assertThat(JsonPath.<Boolean>read(settings.body(), "$.anthropicApiKeyConfigured")).isFalse();
        // Unrelated settings untouched.
        assertThat(JsonPath.<String>read(settings.body(), "$.theme")).isEqualTo("DARK");

        String mine = "(SELECT id FROM users WHERE email = '" + MY_EMAIL + "')";
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = " + mine
                + " AND gemini_api_key_encrypted IS NULL AND anthropic_api_key_encrypted IS NULL")).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE user_id = " + mine)).isZero();
        assertThat(boardFitScore(client, applicationId)).isNull();

        // CV, Job and Application survive.
        assertThat(client.get("/api/cv").statusCode()).isEqualTo(200);
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE id = ?", jobId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE id = ?", applicationId)).isEqualTo(1);

        // The other user is untouched.
        HttpResponse<String> theirs = other.get("/api/settings");
        assertThat(JsonPath.<String>read(theirs.body(), "$.aiProvider")).isEqualTo("ANTHROPIC");
        assertThat(JsonPath.<Boolean>read(theirs.body(), "$.anthropicApiKeyConfigured")).isTrue();
        assertThat((Integer) JsonPath.read(theirs.body(), "$.minimumFitScore")).isEqualTo(55);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", theirJobId)).isEqualTo(1);
    }

    @Test
    void removingAiConfigurationIsIdempotentAndRequiresLogin() throws Exception {
        assertThat(client.deleteRequest("/api/settings/ai").statusCode()).isEqualTo(204);
        assertThat(client.deleteRequest("/api/settings/ai").statusCode()).isEqualTo(204);
        assertThat(new TestApiClient(port).deleteRequest("/api/settings/ai").statusCode()).isEqualTo(401);
    }

    private static void configure(TestApiClient api, String provider, String model, int minScore, String theme)
            throws Exception {
        HttpResponse<String> put = api.putJson("/api/settings", """
                {"theme":"%s","timezone":"UTC","visibleBoardStatuses":[%s],"minimumFitScore":%d,"aiProvider":"%s","aiModel":"%s"}
                """.formatted(theme, ALL_STATUSES, minScore, provider, model));
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private static Object boardFitScore(TestApiClient api, int applicationId) throws Exception {
        List<Object> scores = JsonPath.read(api.get("/api/board").body(), "$[?(@.applicationId==" + applicationId + ")].fitScore");
        return scores.get(0);
    }
}
