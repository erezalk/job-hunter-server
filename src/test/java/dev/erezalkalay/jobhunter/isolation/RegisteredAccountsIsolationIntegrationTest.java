package dev.erezalkalay.jobhunter.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The multi-user model with accounts created the real way, through sign-up:
 * A builds a full Board (job, company, ticket, notes, history, stage, AI match,
 * CV, settings with an AI key, MCP token); B then signs up and must start
 * empty, see none of A's data under any guessed id, and change nothing of A's
 * — neither by signing up nor by anything B does afterwards.
 */
class RegisteredAccountsIsolationIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "registered-password-1";
    private static final String COMPANY = "Shared Name GmbH";
    private static final String TITLE = "Platform Engineer";
    private static final String A_SECRET_TEXT = "A-only private description";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private FakeAiProviderState fakeAi;

    private String run;
    private TestApiClient a;
    private long aUserId;
    private long aJob;
    private long aApplication;
    private long aStage;
    private String aToken;

    @BeforeEach
    void setUp() throws Exception {
        run = System.nanoTime() + "-" + SEQ.incrementAndGet();
        fakeAi.reset();
        a = signUp("reg-a-" + run + "@example.com");
        aUserId = JsonPath.<Number>read(a.get("/api/auth/me").body(), "$.id").longValue();

        assertThat(a.postMultipartFile("/api/cv", "file", "a.pdf", "application/pdf",
                TestFiles.pdfWithText("Alice registered CV: Staff engineer, Java, Kubernetes, PostgreSQL.")).statusCode()).isEqualTo(201);
        assertThat(a.putJson("/api/settings", "{\"theme\":\"DARK\",\"timezone\":\"Europe/Berlin\",\"visibleBoardStatuses\":[\"NEW\",\"CV_SENT\",\"LOW_MATCH\"],"
                + "\"minimumFitScore\":55,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(a.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\""
                + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode()).isEqualTo(200);
        aToken = JsonPath.read(a.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");

        HttpResponse<String> added = a.postJson("/api/board/jobs", "{\"title\":\"" + TITLE + "\",\"companyName\":\"" + COMPANY
                + "\",\"description\":\"" + A_SECRET_TEXT + "\"}");
        assertThat(added.statusCode()).isEqualTo(201);
        aJob = JsonPath.<Number>read(added.body(), "$.jobId").longValue();
        aApplication = JsonPath.<Number>read(added.body(), "$.id").longValue();
        assertThat(a.patchJson(app(aApplication) + "/notes", "{\"notes\":\"A-only notes\"}").statusCode()).isEqualTo(200);
        assertThat(a.patchJson(app(aApplication) + "/status", "{\"statusCode\":\"CV_SENT\",\"notes\":\"A-only history\"}")
                .statusCode()).isEqualTo(200);
        HttpResponse<String> stage = a.postJson(app(aApplication) + "/stages", "{\"name\":\"A-only interview\",\"scheduledAt\":\""
                + Instant.now().plus(2, ChronoUnit.DAYS) + "\"}");
        assertThat(stage.statusCode()).isEqualTo(201);
        aStage = JsonPath.<Number>read(stage.body(), "$.id").longValue();
        fakeAi.setNextResult(new AiMatchResult(88, List.of("A-only skill"), List.of(), List.of()));
        assertThat(a.postJson("/api/jobs/" + aJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        fakeAi.reset();
    }

    @Test
    void signingUpBChangesNothingOfAAndBStartsEmpty() throws Exception {
        String before = snapshotOfA();

        TestApiClient b = signUp("reg-b-" + run + "@example.com");

        assertThat(snapshotOfA()).isEqualTo(before);
        assertThat(b.get("/api/board").body()).isEqualTo("[]");
        assertThat(b.get("/api/stages/upcoming").body()).isEqualTo("[]");
        assertThat(b.get("/api/applications").body()).doesNotContain(TITLE);
        assertThat(JsonPath.<List<Object>>read(b.get("/api/job-data/export").body(), "$.jobs")).isEmpty();
        assertThat((Object) JsonPath.read(b.get("/api/cv").body(), "$.cv")).isNull();
        assertThat(b.getBytes("/api/cv/file").statusCode()).isEqualTo(404);
        assertThat((Boolean) JsonPath.read(b.get("/api/settings/integrations/mcp-token").body(), "$.configured")).isFalse();

        String bSettings = b.get("/api/settings").body();
        assertThat(JsonPath.<String>read(bSettings, "$.theme")).isEqualTo("SYSTEM");
        assertThat((Integer) JsonPath.read(bSettings, "$.minimumFitScore")).isEqualTo(70);
        assertThat(JsonPath.<List<String>>read(bSettings, "$.visibleBoardStatuses")).doesNotContain("LOW_MATCH");
        assertThat((Object) JsonPath.read(bSettings, "$.aiProvider")).isNull();
        assertThat((Boolean) JsonPath.read(bSettings, "$.anthropicApiKeyConfigured")).isFalse();
        // Without A's key B cannot reach a provider either.
        assertThat(b.postJson("/api/settings/ai/test-connection", "{}").body()).doesNotContain(FakeAiProviderState.VALID_API_KEY);
    }

    @Test
    void bCannotReachAnyOfAsResourcesByGuessedIds() throws Exception {
        TestApiClient b = signUp("reg-b-" + run + "@example.com");
        HttpResponse<String> own = b.postJson("/api/board/jobs", "{\"title\":\"B own\",\"companyName\":\"B Co\"}");
        long bApplication = JsonPath.<Number>read(own.body(), "$.id").longValue();
        String before = snapshotOfA();

        List<String> reads = List.of("/api/jobs/" + aJob, "/api/jobs/" + aJob + "/match", app(aApplication),
                app(aApplication) + "/stages", app(aApplication) + "/status-history");
        for (String path : reads) {
            HttpResponse<String> response = b.get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).doesNotContain(TITLE).doesNotContain("A-only").doesNotContain(A_SECRET_TEXT);
        }
        assertThat(b.patchJson("/api/jobs/" + aJob, "{\"title\":\"Hijacked\",\"companyName\":\"X\"}").statusCode()).isEqualTo(404);
        assertThat(b.postJson("/api/jobs/" + aJob + "/match/evaluate?force=true", "{}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/status", "{\"statusCode\":\"OFFER\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/move", "{\"statusCode\":\"OFFER\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/starred", "{\"starred\":true}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/notes", "{\"notes\":\"B was here\"}").statusCode()).isEqualTo(404);
        assertThat(b.postJson(app(aApplication) + "/stages", "{\"name\":\"B stage\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/stages/" + aStage, "{\"name\":\"B renamed\"}").statusCode()).isEqualTo(404);
        assertThat(b.deleteRequest(app(aApplication) + "/stages/" + aStage).statusCode()).isEqualTo(404);
        // A's stage id under B's own ticket is not found either.
        assertThat(b.patchJson(app(bApplication) + "/stages/" + aStage, "{\"name\":\"B renamed\"}").statusCode()).isEqualTo(404);
        assertThat(b.deleteRequest(app(bApplication) + "/stages/" + aStage).statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(bApplication) + "/move", "{\"statusCode\":\"NEW\",\"beforeApplicationId\":" + aApplication + "}")
                .statusCode()).isIn(400, 404);
        assertThat(b.deleteRequest(app(aApplication)).statusCode()).isEqualTo(404);

        // A sweep of neighbouring ids.
        for (long delta = -3; delta <= 3; delta++) {
            for (String path : List.of("/api/jobs/" + (aJob + delta), app(aApplication + delta),
                    app(aApplication + delta) + "/stages", "/api/jobs/" + (aJob + delta) + "/match")) {
                HttpResponse<String> response = b.get(path);
                if (response.statusCode() == 200) {
                    assertThat(response.body()).as(path).doesNotContain("A-only").doesNotContain(A_SECRET_TEXT);
                }
            }
        }
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    @Test
    void sameCompanyAndTitleAreTrackedIndependentlyWithSeparateCompanies() throws Exception {
        TestApiClient b = signUp("reg-b-" + run + "@example.com");
        String before = snapshotOfA();

        HttpResponse<String> theirs = b.postJson("/api/board/jobs", "{\"title\":\"" + TITLE + "\",\"companyName\":\"" + COMPANY + "\"}");

        assertThat(theirs.statusCode()).as("not A's existing job: B has none yet").isEqualTo(201);
        long bJob = JsonPath.<Number>read(theirs.body(), "$.jobId").longValue();
        assertThat(bJob).isNotEqualTo(aJob);
        long bUserId = JsonPath.<Number>read(b.get("/api/auth/me").body(), "$.id").longValue();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE name_key = 'shared name gmbh' AND user_id = ?", aUserId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE name_key = 'shared name gmbh' AND user_id = ?", bUserId)).isEqualTo(1);
        assertThat(b.get("/api/jobs/" + bJob).body()).doesNotContain(A_SECRET_TEXT);
        // Adding it again is B's own duplicate (200), never A's.
        HttpResponse<String> again = b.postJson("/api/board/jobs", "{\"title\":\"" + TITLE + "\",\"companyName\":\"" + COMPANY + "\"}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(again.body(), "$.jobId").longValue()).isEqualTo(bJob);

        assertThat(b.deleteRequest(app(JsonPath.<Number>read(theirs.body(), "$.id").longValue())).statusCode()).isEqualTo(204);
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    @Test
    void settingsKeysAndTokensStayPerAccount() throws Exception {
        TestApiClient b = signUp("reg-b-" + run + "@example.com");
        String before = snapshotOfA();

        assertThat(b.putJson("/api/settings", "{\"theme\":\"LIGHT\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":[\"OFFER\"],"
                + "\"minimumFitScore\":90,\"aiProvider\":\"GEMINI\",\"aiModel\":\"b-model\"}").statusCode()).isEqualTo(200);
        assertThat(b.putJson("/api/settings/ai/api-key", "{\"provider\":\"GEMINI\",\"apiKey\":\"b-key\"}").statusCode()).isEqualTo(200);
        assertThat(b.deleteRequest("/api/settings/ai").statusCode()).isEqualTo(204);
        String bToken = JsonPath.read(b.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        assertThat(bToken).isNotEqualTo(aToken);
        assertThat(b.deleteRequest("/api/settings/integrations/mcp-token").statusCode()).isEqualTo(204);

        assertThat(snapshotOfA()).isEqualTo(before);
        // A's token still adds to A's Board only.
        HttpResponse<String> mcp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/integrations/jobs"))
                .header("Authorization", "Bearer " + aToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Via A token\",\"companyName\":\"A Co\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(201);
        assertThat(a.get("/api/board").body()).contains("Via A token");
        assertThat(b.get("/api/board").body()).doesNotContain("Via A token");
    }

    @Test
    void oneUsersPasswordChangeDoesNotAffectAnother() throws Exception {
        TestApiClient b = signUp("reg-b-" + run + "@example.com");
        String aHash = testData.strings("SELECT password_hash FROM users WHERE id = ?", aUserId).getFirst();
        String before = snapshotOfA();

        assertThat(b.changePassword(PASSWORD, "b-new-password-1", "b-new-password-1").statusCode()).isEqualTo(204);

        assertThat(a.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(testData.strings("SELECT password_hash FROM users WHERE id = ?", aUserId)).containsExactly(aHash);
        assertThat(new TestApiClient(port).login("reg-a-" + run + "@example.com", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== Helpers =====================

    private TestApiClient signUp(String email) throws Exception {
        TestApiClient api = new TestApiClient(port);
        assertThat(api.register(email, PASSWORD, PASSWORD).statusCode()).isEqualTo(201);
        return api;
    }

    /** All of A's data the API shows, plus A's settings/key/token/CV rows as stored. */
    private String snapshotOfA() throws Exception {
        ObjectNode export = (ObjectNode) JSON.readTree(a.get("/api/job-data/export").body());
        export.remove("exportedAt");
        return export + "\n" + a.get("/api/board").body() + "\n" + a.get("/api/jobs/" + aJob).body()
                + "\n" + a.get(app(aApplication)).body().replaceAll("\"timestamp\":\"[^\"]*\"", "")
                + "\n" + a.get(app(aApplication) + "/stages").body() + "\n" + a.get(app(aApplication) + "/status-history").body()
                + "\n" + a.get("/api/jobs/" + aJob + "/match").body() + "\n" + a.get("/api/settings").body()
                + "\n" + a.get("/api/cv").body() + "\n" + a.get("/api/settings/integrations/mcp-token").body()
                + "\n" + testData.strings("SELECT coalesce(anthropic_api_key_encrypted, '') || updated_at FROM settings WHERE user_id = ?", aUserId)
                + "\n" + testData.strings("SELECT token_hash FROM integration_tokens WHERE user_id = ?", aUserId)
                + "\n" + testData.strings("SELECT id || storage_path || is_primary FROM cvs WHERE user_id = ? ORDER BY id", aUserId)
                + "\n" + testData.strings("SELECT email || password_hash || updated_at FROM users WHERE id = ?", aUserId);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String app(long applicationId) {
        return "/api/applications/" + applicationId;
    }
}
