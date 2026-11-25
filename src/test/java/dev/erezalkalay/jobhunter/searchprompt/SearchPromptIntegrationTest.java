package dev.erezalkalay.jobhunter.searchprompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Settings → Job Search → Search Prompt (session API) and its read-only bearer-token view
 * for MCP clients (get_search_prompt). Every test uses freshly created users
 * so exact values can be asserted on the shared Testcontainers database.
 */
@ExtendWith(OutputCaptureExtension.class)
class SearchPromptIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "search-prompt-password-123";
    private static final String URL = "/api/settings/search-prompt";
    private static final String INTEGRATION_URL = "/api/integrations/search-prompt";

    /** Free-form text with everything that must survive byte for byte. */
    private static final String RICH_PROMPT = String.join("\n",
            "# Job search — Berlin 🚀",
            "",
            "Find **senior backend** roles (Java/Kotlin) that match my CV.",
            "",
            "1. Skip companies from get_excluded_companies.",
            "2. Remote *or* hybrid; salary ≥ 85.000 €.",
            "   - Nested bullet with \ttab and  double  spaces",
            "",
            "> Quote: \"don't\" use 'quotes' \\ or backslashes \\n literally",
            "<script>alert('not HTML, plain text')</script> &amp; <b>bold</b>",
            "Unicode: Ünïcödé · עברית · 日本語 · emoji 👩‍💻 · zero-width​joiner",
            "Windows line\r\nending and a trailing space ",
            "",
            "");

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private JdbcTemplate jdbc;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private String run;

    @BeforeEach
    void setUp() {
        run = String.valueOf(System.nanoTime());
    }

    // --- Session API ------------------------------------------------------------

    @Test
    void aNewUserHasNoPrompt() throws Exception {
        HttpResponse<String> response = newUser("alice").get(URL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.prompt")).isEmpty();
        assertThat((Object) JsonPath.read(response.body(), "$.updatedAt")).isNull();
    }

    @Test
    void createsAndRetrievesThePromptExactlyAsEntered() throws Exception {
        TestApiClient alice = newUser("alice");

        HttpResponse<String> saved = save(alice, RICH_PROMPT);

        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(saved.body(), "$.prompt")).isEqualTo(RICH_PROMPT);
        assertThat(JsonPath.<String>read(saved.body(), "$.updatedAt")).isNotBlank();
        assertThat(promptOf(alice)).isEqualTo(RICH_PROMPT);
        // Persisted verbatim in the TEXT column (no trimming, no line-ending or Unicode normalization).
        assertThat(jdbc.queryForObject("SELECT prompt FROM search_prompts WHERE user_id = ?", String.class, userId("alice")))
                .isEqualTo(RICH_PROMPT);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'search_prompts' AND column_name = 'prompt'", String.class)).isEqualTo("text");
    }

    @Test
    void updatingReplacesTheTextInTheSameRow() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, "First version");
        long id = jdbc.queryForObject("SELECT id FROM search_prompts WHERE user_id = ?", Long.class, userId("alice"));
        Instant firstUpdate = Instant.parse(JsonPath.read(alice.get(URL).body(), "$.updatedAt"));

        HttpResponse<String> updated = save(alice, "Second version\n\nwith a new paragraph");

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(promptOf(alice)).isEqualTo("Second version\n\nwith a new paragraph");
        assertThat(Instant.parse(JsonPath.read(updated.body(), "$.updatedAt"))).isAfterOrEqualTo(firstUpdate);
        assertThat(jdbc.queryForList("SELECT id FROM search_prompts WHERE user_id = ?", Long.class, userId("alice")))
                .containsExactly(id);
    }

    @Test
    void acceptsAVeryLongPromptUpToTheLimitAndRejectsLonger() throws Exception {
        TestApiClient alice = newUser("alice");
        String longest = ("Line of instructions ✓\n").repeat(SearchPrompt.PROMPT_MAX_LENGTH / 23)
                + "x".repeat(SearchPrompt.PROMPT_MAX_LENGTH % 23);
        assertThat(longest).hasSize(SearchPrompt.PROMPT_MAX_LENGTH);

        assertThat(save(alice, longest).statusCode()).isEqualTo(200);
        assertThat(promptOf(alice)).isEqualTo(longest);

        HttpResponse<String> tooLong = save(alice, longest + "x");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tooLong.body(), "$.fieldErrors[0].field")).isEqualTo("prompt");
        assertThat(promptOf(alice)).isEqualTo(longest);
    }

    @Test
    void savingAnEmptyOrWhitespaceOnlyTextClearsThePrompt() throws Exception {
        TestApiClient alice = newUser("alice");

        for (String blank : List.of("", " ", "\n\n", " \t\r\n ")) {
            save(alice, "Something");
            HttpResponse<String> cleared = save(alice, blank);
            assertThat(cleared.statusCode()).as("[%s]", blank).isEqualTo(200);
            assertThat(JsonPath.<String>read(cleared.body(), "$.prompt")).isEmpty();
            assertThat((Object) JsonPath.read(cleared.body(), "$.updatedAt")).isNull();
            assertThat(promptOf(alice)).isEmpty();
            assertThat(rowCount("alice")).isZero();
        }
        // Clearing when nothing is saved is fine too.
        assertThat(save(alice, "").statusCode()).isEqualTo(200);
    }

    @Test
    void deleteClearsThePromptAndIsIdempotent() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, RICH_PROMPT);

        assertThat(alice.deleteRequest(URL).statusCode()).isEqualTo(204);
        assertThat(promptOf(alice)).isEmpty();
        assertThat(rowCount("alice")).isZero();
        assertThat(alice.deleteRequest(URL).statusCode()).isEqualTo(204);

        // A new prompt can be created afterwards.
        assertThat(save(alice, "Again").statusCode()).isEqualTo(200);
        assertThat(promptOf(alice)).isEqualTo("Again");
    }

    @Test
    void rejectsAMissingPromptAndMalformedBodies() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, "Keep me");

        for (String json : List.of("{}", "{\"prompt\":null}")) {
            HttpResponse<String> response = alice.putJson(URL, json);
            assertThat(response.statusCode()).as(json).isEqualTo(400);
            assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].field")).isEqualTo("prompt");
        }
        assertThat(alice.putJson(URL, "not json").statusCode()).isEqualTo(400);
        assertThat(alice.putJson(URL, "{\"prompt\":[\"a\"]}").statusCode()).isEqualTo(400);
        assertThat(alice.putJson(URL, "{\"prompt\":\"NUL \\u0000 byte\"}").statusCode()).isEqualTo(400);
        assertThat(promptOf(alice)).isEqualTo("Keep me");
    }

    @Test
    void requiresALoggedInSessionAndCsrf() throws Exception {
        TestApiClient anonymous = new TestApiClient(port);
        anonymous.get("/api/auth/csrf");
        assertThat(anonymous.get(URL).statusCode()).isEqualTo(401);
        assertThat(anonymous.putJson(URL, body("x")).statusCode()).isEqualTo(401);
        assertThat(anonymous.deleteRequest(URL).statusCode()).isEqualTo(401);

        TestApiClient alice = newUser("alice");
        HttpRequest noCsrf = HttpRequest.newBuilder(URI.create("http://localhost:" + port + URL))
                .header("Content-Type", "application/json")
                .header("Cookie", "JSESSIONID=" + alice.cookie("JSESSIONID"))
                .PUT(BodyPublishers.ofString(body("cross-site")))
                .build();
        assertThat(bare.send(noCsrf, BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(promptOf(alice)).isEmpty();
    }

    @Test
    void eachUserOnlyEverSeesAndChangesTheirOwnPrompt() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        TestApiClient carol = newUser("carol");
        save(alice, "Alice only");
        save(bob, "Bob only");

        assertThat(promptOf(alice)).isEqualTo("Alice only");
        assertThat(promptOf(bob)).isEqualTo("Bob only");
        assertThat(promptOf(carol)).isEmpty();

        save(bob, "Bob changed");
        assertThat(bob.deleteRequest(URL).statusCode()).isEqualTo(204);
        assertThat(promptOf(alice)).isEqualTo("Alice only");
        // There is no parameter that could select another user.
        assertThat(JsonPath.<String>read(carol.get(URL + "?userId=" + userId("alice")).body(), "$.prompt")).isEmpty();
        assertThat(carol.get(URL + "/" + userId("alice")).statusCode()).isEqualTo(404);
    }

    // --- Integration (MCP) endpoint ---------------------------------------------------

    @Test
    void theIntegrationEndpointReturnsTheTokenUsersPromptVerbatim() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, RICH_PROMPT);
        String token = generateToken(alice);

        HttpResponse<String> response = bearer("GET", INTEGRATION_URL, token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(JsonPath.<String>read(response.body(), "$.prompt")).isEqualTo(RICH_PROMPT);
        assertThat(JsonPath.<String>read(response.body(), "$.updatedAt")).isNotBlank();
    }

    @Test
    void theIntegrationEndpointSeesEditsImmediately() throws Exception {
        TestApiClient alice = newUser("alice");
        String token = generateToken(alice);
        save(alice, "Version 1");
        assertThat(JsonPath.<String>read(bearer("GET", INTEGRATION_URL, token).body(), "$.prompt")).isEqualTo("Version 1");

        save(alice, "Version 2");
        assertThat(JsonPath.<String>read(bearer("GET", INTEGRATION_URL, token).body(), "$.prompt")).isEqualTo("Version 2");

        alice.deleteRequest(URL);
        assertThat(bearer("GET", INTEGRATION_URL, token).statusCode()).isEqualTo(404);
    }

    @Test
    void theIntegrationEndpointAnswers404WithACodeWhenNoPromptIsConfigured() throws Exception {
        String token = generateToken(newUser("alice"));

        HttpResponse<String> response = bearer("GET", INTEGRATION_URL, token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("SEARCH_PROMPT_NOT_FOUND");
        assertThat(bearer("GET", "/api/integrations/search-prompt-unknown", token).body()).doesNotContain("SEARCH_PROMPT_NOT_FOUND");
    }

    @Test
    void theIntegrationEndpointIsolatesUsers() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        save(alice, "Alice only");
        String aliceToken = generateToken(alice);
        String bobToken = generateToken(bob);

        assertThat(bearer("GET", INTEGRATION_URL, bobToken).statusCode()).isEqualTo(404);
        assertThat(bearer("GET", INTEGRATION_URL + "?userId=" + userId("alice"), bobToken).statusCode()).isEqualTo(404);

        save(bob, "Bob only");
        assertThat(JsonPath.<String>read(bearer("GET", INTEGRATION_URL, bobToken).body(), "$.prompt")).isEqualTo("Bob only");
        assertThat(JsonPath.<String>read(bearer("GET", INTEGRATION_URL + "?userId=" + userId("bob"), aliceToken).body(), "$.prompt"))
                .isEqualTo("Alice only");
    }

    @Test
    void theIntegrationEndpointRejectsMissingInvalidRevokedTokensAndSessions() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, "Secret instructions");
        String token = generateToken(alice);
        String second = generateToken(alice);
        long tokenId = JsonPath.<Number>read(alice.get("/api/settings/integrations/tokens").body(), "$[0].id").longValue();

        assertThat(bearer("GET", INTEGRATION_URL, null).statusCode()).isEqualTo(401);
        assertThat(bearer("GET", INTEGRATION_URL, "jh_not-a-real-token").statusCode()).isEqualTo(401);
        assertThat(bearer("GET", INTEGRATION_URL, "jh_not-a-real-token").body()).doesNotContain("Secret instructions");
        assertThat(alice.get(INTEGRATION_URL).statusCode()).isEqualTo(401);

        assertThat(alice.deleteRequest("/api/settings/integrations/tokens/" + tokenId).statusCode()).isEqualTo(204);
        assertThat(bearer("GET", INTEGRATION_URL, token).statusCode()).isEqualTo(401);
        // The user's other token keeps working.
        assertThat(bearer("GET", INTEGRATION_URL, second).statusCode()).isEqualTo(200);
    }

    @Test
    void aBearerTokenCanNeitherReachTheSessionApiNorWriteThePrompt() throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, "Unchanged");
        String token = generateToken(alice);

        assertThat(bearer("GET", URL, token).statusCode()).isEqualTo(401);
        assertThat(bearer("PUT", URL, token).statusCode()).isIn(401, 403);
        assertThat(bearer("DELETE", URL, token).statusCode()).isIn(401, 403);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(bearer(method, INTEGRATION_URL, token).statusCode()).as(method).isEqualTo(405);
        }
        assertThat(promptOf(alice)).isEqualTo("Unchanged");
    }

    @Test
    void neverLogsThePromptOrTheToken(CapturedOutput output) throws Exception {
        TestApiClient alice = newUser("alice");
        save(alice, "Confidential search instructions XYZ-123");
        String token = generateToken(alice);
        bearer("GET", INTEGRATION_URL, token);
        bearer("GET", INTEGRATION_URL, "jh_not-a-real-token");
        save(alice, "x".repeat(SearchPrompt.PROMPT_MAX_LENGTH + 1));

        assertThat(output.getAll()).doesNotContain("Confidential search instructions").doesNotContain(token);
    }

    // --- helpers -------------------------------------------------------------------

    private String email(String name) {
        return name + "-prompt-" + run + "@jobhunter.local";
    }

    private long userId(String name) {
        return testData.userId(email(name));
    }

    private int rowCount(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM search_prompts WHERE user_id = ?", Integer.class, userId(name));
    }

    private TestApiClient newUser(String name) throws Exception {
        testData.createUserIfMissing(email(name), PASSWORD);
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(email(name), PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private static String body(String prompt) {
        return "{\"prompt\":" + TestApiClient.jsonString(prompt) + "}";
    }

    private static HttpResponse<String> save(TestApiClient client, String prompt) throws Exception {
        return client.putJson(URL, body(prompt));
    }

    private static String promptOf(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.get(URL);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.prompt");
    }

    private static String generateToken(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.postJson("/api/settings/integrations/tokens", "{}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private HttpResponse<String> bearer(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, method.equals("GET") ? BodyPublishers.noBody() : BodyPublishers.ofString(body("hijack")));
        if (!method.equals("GET")) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return bare.send(request.build(), BodyHandlers.ofString());
    }
}
