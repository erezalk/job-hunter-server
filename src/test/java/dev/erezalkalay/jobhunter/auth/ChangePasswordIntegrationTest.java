package dev.erezalkalay.jobhunter.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * POST /api/auth/change-password: verification, policy, and the session rule
 * (this session continues under a new id; every other session of the user
 * ends; other users are untouched).
 */
@ExtendWith(OutputCaptureExtension.class)
class ChangePasswordIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String OLD = "old-password-123";
    private static final String NEW = "new-password-456";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private String email;
    private TestApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        email = "change-" + System.nanoTime() + "-" + SEQ.incrementAndGet() + "@example.com";
        api = new TestApiClient(port);
        assertThat(api.register(email, OLD, OLD).statusCode()).isEqualTo(201);
    }

    // ===================== Success =====================

    @Test
    void theNewPasswordReplacesTheOldOne(CapturedOutput output) throws Exception {
        String hashBefore = hash();

        HttpResponse<String> response = api.changePassword(OLD, NEW, NEW);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(new TestApiClient(port).login(email, OLD).statusCode()).isEqualTo(401);
        assertThat(new TestApiClient(port).login(email, NEW).statusCode()).isEqualTo(200);
        assertThat(hash()).startsWith("$2").isNotEqualTo(hashBefore).doesNotContain(NEW);
        assertThat(output.getAll()).doesNotContain(OLD).doesNotContain(NEW).doesNotContain(hash()).doesNotContain(hashBefore);
    }

    @Test
    void thisSessionStaysLoggedInUnderANewSessionId() throws Exception {
        String sessionBefore = api.cookie("JSESSIONID");

        assertThat(api.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(204);

        assertThat(api.cookie("JSESSIONID")).isNotNull().isNotEqualTo(sessionBefore);
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(api.postJson("/api/board/jobs", "{\"title\":\"After change\",\"companyName\":\"Co\"}").statusCode())
                .isEqualTo(201);
        // A copy of the old session cookie (e.g. a stolen one) no longer works.
        assertThat(meWithSessionCookie(sessionBefore)).isEqualTo(401);
    }

    @Test
    void everyOtherSessionOfTheUserEnds() throws Exception {
        TestApiClient laptop = new TestApiClient(port);
        assertThat(laptop.login(email, OLD).statusCode()).isEqualTo(200);
        TestApiClient phone = new TestApiClient(port);
        assertThat(phone.login(email.toUpperCase(), OLD).statusCode()).isEqualTo(200);
        assertThat(laptop.get("/api/auth/me").statusCode()).isEqualTo(200);

        assertThat(api.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(204);

        for (TestApiClient other : List.of(laptop, phone)) {
            HttpResponse<String> me = other.get("/api/auth/me");
            assertThat(me.statusCode()).isEqualTo(401);
            assertThat(me.body()).doesNotContain(email);
            // Ended for good, not just for one request.
            assertThat(other.get("/api/board").statusCode()).isEqualTo(401);
            assertThat(other.postJson("/api/board/jobs", "{\"title\":\"Late write\",\"companyName\":\"Co\"}").statusCode())
                    .isEqualTo(401);
        }
        assertThat(testData.count("SELECT count(*) FROM jobs j JOIN users u ON u.id = j.user_id WHERE u.email = ? "
                + "AND j.title = 'Late write'", email)).isZero();
        // Logging in again with the new password works; that session is not affected.
        assertThat(laptop.login(email, NEW).statusCode()).isEqualTo(200);
        assertThat(laptop.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void theSignUpSessionAndARotatedSessionAreRevocableToo() throws Exception {
        // `api` holds the session created by sign-up; change the password from another session.
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(email, OLD).statusCode()).isEqualTo(200);
        assertThat(other.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(204);
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(401);

        // `other` now runs under a rotated session id; a later change elsewhere still ends it.
        TestApiClient third = new TestApiClient(port);
        assertThat(third.login(email, NEW).statusCode()).isEqualTo(200);
        assertThat(third.changePassword(NEW, OLD, OLD).statusCode()).isEqualTo(204);
        assertThat(other.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(third.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void anotherUsersSessionsAndPasswordAreUntouched() throws Exception {
        String otherEmail = "bystander-" + email;
        TestApiClient bystander = new TestApiClient(port);
        assertThat(bystander.register(otherEmail, OLD, OLD).statusCode()).isEqualTo(201);
        String bystanderHash = testData.strings("SELECT password_hash FROM users WHERE email = ?", otherEmail).getFirst();

        assertThat(api.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(204);

        assertThat(bystander.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(testData.strings("SELECT password_hash FROM users WHERE email = ?", otherEmail)).containsExactly(bystanderHash);
        assertThat(new TestApiClient(port).login(otherEmail, OLD).statusCode()).isEqualTo(200);
        assertThat(new TestApiClient(port).login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
    }

    @Test
    void changingThePasswordLeavesAllOtherAccountDataUnchanged() throws Exception {
        // Jobs, ticket notes, CV, settings, AI key and MCP token.
        long application = JsonPath.<Number>read(api.postJson("/api/board/jobs",
                "{\"title\":\"Kept job\",\"companyName\":\"Kept Co\",\"description\":\"desc\"}").body(), "$.id").longValue();
        assertThat(api.patchJson("/api/applications/" + application + "/notes", "{\"notes\":\"kept notes\"}").statusCode())
                .isEqualTo(200);
        assertThat(api.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Kept CV text with enough characters to be extracted.")).statusCode()).isEqualTo(201);
        assertThat(api.putJson("/api/settings", "{\"theme\":\"DARK\",\"timezone\":\"Europe/Berlin\",\"visibleBoardStatuses\":[\"NEW\",\"OFFER\"],"
                + "\"minimumFitScore\":55,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(api.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\""
                + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode()).isEqualTo(200);
        String token = JsonPath.read(api.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        long userId = testData.userId(email);
        String before = state(userId);

        assertThat(api.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(204);

        assertThat(state(userId)).isEqualTo(before);
        // The MCP token is a separate credential and keeps working.
        HttpResponse<String> mcp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/integrations/jobs"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Via MCP\",\"companyName\":\"Kept Co\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(201);
    }

    // ===================== Rejections =====================

    @Test
    void aWrongCurrentPasswordIsRejectedWithoutEndingTheSession() throws Exception {
        String hashBefore = hash();
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(email, OLD).statusCode()).isEqualTo(200);

        for (String wrong : List.of("wrong-password", OLD.toUpperCase(), OLD + " ", "p".repeat(200))) {
            HttpResponse<String> response = api.changePassword(wrong, NEW, NEW);
            assertThat(response.statusCode()).as(wrong).isEqualTo(400);
            assertThat(fieldErrors(response)).containsExactly(Map.entry("currentPassword", "Current password is incorrect"));
        }

        assertThat(hash()).isEqualTo(hashBefore);
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(other.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(new TestApiClient(port).login(email, OLD).statusCode()).isEqualTo(200);
    }

    @Test
    void theNewPasswordFollowsTheSignUpPolicy() throws Exception {
        String hashBefore = hash();
        for (String invalid : List.of("short", "x".repeat(73), "é".repeat(37), "         ")) {
            HttpResponse<String> response = api.changePassword(OLD, invalid, invalid);
            assertThat(response.statusCode()).as(invalid).isEqualTo(400);
            assertThat(fieldErrors(response)).as(invalid).containsOnlyKeys("newPassword");
        }
        assertThat(hash()).isEqualTo(hashBefore);
    }

    @Test
    void theConfirmationMustMatch() throws Exception {
        HttpResponse<String> response = api.changePassword(OLD, NEW, NEW + "x");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldErrors(response)).containsOnlyKeys("confirmNewPassword");
        assertThat(new TestApiClient(port).login(email, OLD).statusCode()).isEqualTo(200);
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() throws Exception {
        String hashBefore = hash();

        HttpResponse<String> response = api.changePassword(OLD, OLD, OLD);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldErrors(response)).containsExactly(
                Map.entry("newPassword", "New password must be different from the current password"));
        assertThat(hash()).isEqualTo(hashBefore);
    }

    @Test
    void missingFieldsAreReportedTogether() throws Exception {
        HttpResponse<String> response = api.postJson("/api/auth/change-password", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldErrors(response)).containsOnlyKeys("currentPassword", "newPassword", "confirmNewPassword");
        assertThat(response.body()).doesNotContain(OLD);
    }

    @Test
    void requiresALoggedInBrowserSession() throws Exception {
        TestApiClient anonymous = new TestApiClient(port);
        anonymous.get("/api/auth/csrf");
        assertThat(anonymous.changePassword(OLD, NEW, NEW).statusCode()).isEqualTo(401);

        // An MCP token is not a login: rejected here like on every non-integration endpoint.
        String token = JsonPath.read(api.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        HttpResponse<String> withToken = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/change-password"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"" + OLD + "\",\"newPassword\":\"" + NEW
                        + "\",\"confirmNewPassword\":\"" + NEW + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(withToken.statusCode()).isEqualTo(401);

        assertThat(new TestApiClient(port).login(email, OLD).statusCode()).isEqualTo(200);
    }

    @Test
    void requiresTheCsrfToken() throws Exception {
        String hashBefore = hash();

        HttpResponse<String> response = api.postJsonWithoutCsrfHeader("/api/auth/change-password",
                "{\"currentPassword\":\"" + OLD + "\",\"newPassword\":\"" + NEW + "\",\"confirmNewPassword\":\"" + NEW + "\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(hash()).isEqualTo(hashBefore);
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void onlyThePasswordFieldsAreRead() throws Exception {
        long userId = testData.userId(email);

        HttpResponse<String> response = api.postJson("/api/auth/change-password", "{\"currentPassword\":\"" + OLD
                + "\",\"newPassword\":\"" + NEW + "\",\"confirmNewPassword\":\"" + NEW + "\",\"email\":\"hijack@example.com\","
                + "\"userId\":1,\"id\":1}");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(testData.strings("SELECT email FROM users WHERE id = ?", userId)).containsExactly(email);
        assertThat(testData.count("SELECT count(*) FROM users WHERE email = 'hijack@example.com'")).isZero();
        assertThat(new TestApiClient(port).login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
    }

    // ===================== Helpers =====================

    private String hash() {
        return testData.strings("SELECT password_hash FROM users WHERE email = ?", email).getFirst();
    }

    /** Everything the account owns, apart from the password hash. */
    private String state(long userId) {
        return java.util.stream.Stream.of(
                testData.strings("SELECT id || title || coalesce(description, '') || updated_at FROM jobs WHERE user_id = ? ORDER BY id", userId),
                testData.strings("SELECT id || status_code || coalesce(notes, '') || updated_at FROM applications WHERE user_id = ? ORDER BY id", userId),
                testData.strings("SELECT id || storage_path || is_primary || updated_at FROM cvs WHERE user_id = ? ORDER BY id", userId),
                testData.strings("SELECT theme || timezone || visible_board_statuses || minimum_fit_score || coalesce(ai_provider, '') "
                        + "|| coalesce(ai_model, '') || coalesce(anthropic_api_key_encrypted, '') || coalesce(gemini_api_key_encrypted, '') "
                        + "|| updated_at FROM settings WHERE user_id = ?", userId),
                testData.strings("SELECT token_hash || token_suffix || created_at FROM integration_tokens WHERE user_id = ?", userId),
                testData.strings("SELECT email || full_name || created_at FROM users WHERE id = ?", userId))
                .map(Object::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private int meWithSessionCookie(String sessionId) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/me"))
                .header("Cookie", "JSESSIONID=" + sessionId).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static Map<String, String> fieldErrors(HttpResponse<String> response) {
        List<Map<String, String>> errors = JsonPath.read(response.body(), "$.fieldErrors");
        return errors.stream().collect(java.util.stream.Collectors.toMap(e -> e.get("field"), e -> e.get("message")));
    }
}
