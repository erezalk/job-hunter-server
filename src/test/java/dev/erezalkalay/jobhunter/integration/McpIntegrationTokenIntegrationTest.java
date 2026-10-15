package dev.erezalkalay.jobhunter.integration;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class McpIntegrationTokenIntegrationTest extends IntegrationTestBase {

    private static final String TOKEN_URL = "/api/settings/integrations/mcp-token";
    private static final String OWNER_EMAIL = "test@jobhunter.local";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testDataSupport;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private TestApiClient browser;
    private String company;

    @BeforeEach
    void setUp() throws Exception {
        browser = new TestApiClient(port);
        assertThat(browser.login(OWNER_EMAIL, "test-password-123").statusCode()).isEqualTo(200);
        company = "Mcp Co " + System.nanoTime();
    }

    private String generate(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.postJson(TOKEN_URL, "{}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }

    private HttpResponse<String> addJob(String bearer, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/integrations/jobs"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return bare.send(request.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> bearerGet(String bearer, String path) throws Exception {
        return bare.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), BodyHandlers.ofString());
    }

    private String job(String title) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}";
    }

    private List<String> boardTitles(TestApiClient client) throws Exception {
        return JsonPath.read(client.get("/api/board").body(), "$[?(@.companyName=='" + company + "')].title");
    }

    @Test
    void rawTokenIsReturnedOnlyOnGenerationAndOnlyItsDigestIsStored() throws Exception {
        String token = generate(browser);

        assertThat(token).startsWith("jh_").hasSizeGreaterThan(40);
        assertThat(testDataSupport.count("select count(*) from integration_tokens where token_hash = ?", token)).isZero();
        assertThat(testDataSupport.count("select count(*) from integration_tokens where token_hash = ?",
                IntegrationTokenService.hash(token))).isEqualTo(1);
        assertThat(testDataSupport.count("""
                select count(*) from integration_tokens t join users u on u.id = t.user_id
                where u.email = ? and t.token_hash = ?""", OWNER_EMAIL, IntegrationTokenService.hash(token))).isEqualTo(1);

        HttpResponse<String> status = browser.get(TOKEN_URL);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(status.body(), "$.configured")).isTrue();
        assertThat(status.body()).doesNotContain(token);
        assertThat(JsonPath.<String>read(status.body(), "$.suffix")).isEqualTo(token.substring(token.length() - 4));
        assertThat(status.body()).doesNotContain("\"token\"");
    }

    @Test
    void tokenNeverExpiresOnItsOwn() throws Exception {
        String token = generate(browser);
        testDataSupport.update("update integration_tokens set created_at = now() - interval '3 years'");

        assertThat(addJob(token, job("Old Token Role")).statusCode()).isEqualTo(201);
    }

    @Test
    void addJobUsesTheSharedLogicAndLandsOnTheTokenOwnersBoard() throws Exception {
        String token = generate(browser);

        HttpResponse<String> added = addJob(token, """
                {"title":"Backend Engineer","companyName":"%s","location":"Remote","workModel":"Remote",
                 "url":"https://example.com/jobs/1","salaryMin":100,"salaryMax":200,"currency":"USD",
                 "description":"Build things"}""".formatted(company));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(added.body(), "$.created")).isTrue();
        assertThat(JsonPath.<String>read(added.body(), "$.statusCode")).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(added.body(), "$.companyName")).isEqualTo(company);
        assertThat(added.body()).doesNotContain("userId").doesNotContain(OWNER_EMAIL);
        assertThat(boardTitles(browser)).containsExactly("Backend Engineer");

        // Same ticket as the Board's Add Job button: re-adding through the browser endpoint is the idempotent 200.
        HttpResponse<String> viaBrowser = browser.postJson("/api/board/jobs", job("Backend Engineer"));
        assertThat(viaBrowser.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(viaBrowser.body(), "$.id")).isEqualTo(JsonPath.<Integer>read(added.body(), "$.applicationId"));
    }

    @Test
    void ownerIsAlwaysTheTokenUserEvenIfTheBodyNamesAnotherUser() throws Exception {
        testDataSupport.createUserIfMissing("mcp-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("mcp-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);
        String token = generate(browser);

        HttpResponse<String> added = addJob(token, """
                {"title":"Sneaky","companyName":"%s","userId":999999,"email":"mcp-other@jobhunter.local","owner":"x"}"""
                .formatted(company));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(boardTitles(browser)).containsExactly("Sneaky");
        assertThat(boardTitles(other)).isEmpty();

        String otherToken = generate(other);
        assertThat(addJob(otherToken, job("Other Role")).statusCode()).isEqualTo(201);
        assertThat(boardTitles(other)).contains("Other Role");
        assertThat(boardTitles(browser)).doesNotContain("Other Role");
    }

    @Test
    void duplicatesCreateNothingNew() throws Exception {
        String token = generate(browser);
        HttpResponse<String> first = addJob(token, job("Dup Role"));
        HttpResponse<String> second = addJob(token, job("  dup   ROLE "));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(second.body(), "$.created")).isFalse();
        assertThat(JsonPath.<Integer>read(second.body(), "$.applicationId")).isEqualTo(JsonPath.<Integer>read(first.body(), "$.applicationId"));
        assertThat(boardTitles(browser)).hasSize(1);
        assertThat(testDataSupport.count("select count(*) from jobs where company_key = ?", company.toLowerCase())).isEqualTo(1);
    }

    @Test
    void existingAddJobValidationApplies() throws Exception {
        String token = generate(browser);

        assertThat(addJob(token, "{\"companyName\":\"" + company + "\"}").statusCode()).isEqualTo(400);
        assertThat(addJob(token, "{\"title\":\"X\",\"url\":\"ftp://nope\"}").statusCode()).isEqualTo(400);
        assertThat(addJob(token, "{\"title\":\"X\",\"salaryMin\":-1}").statusCode()).isEqualTo(400);
        assertThat(boardTitles(browser)).isEmpty();
    }

    @Test
    void missingInvalidAndSessionOnlyCredentialsAreRejected() throws Exception {
        generate(browser);

        assertThat(addJob(null, job("A")).statusCode()).isEqualTo(401);
        assertThat(addJob("jh_not-a-real-token", job("A")).statusCode()).isEqualTo(401);
        assertThat(addJob("not-even-prefixed", job("A")).statusCode()).isEqualTo(401);
        // A logged-in browser session must not authenticate this endpoint either.
        assertThat(browser.postJson("/api/integrations/jobs", job("A")).statusCode()).isEqualTo(401);
        assertThat(boardTitles(browser)).isEmpty();
    }

    @Test
    void revokedAndReplacedTokensStopWorkingImmediately() throws Exception {
        String first = generate(browser);
        assertThat(addJob(first, job("One")).statusCode()).isEqualTo(201);

        String second = generate(browser);
        assertThat(second).isNotEqualTo(first);
        assertThat(addJob(first, job("Two")).statusCode()).isEqualTo(401);
        assertThat(addJob(second, job("Two")).statusCode()).isEqualTo(201);
        assertThat(testDataSupport.count("select count(*) from integration_tokens t join users u on u.id = t.user_id where u.email = ?",
                OWNER_EMAIL)).isEqualTo(1);

        assertThat(browser.deleteRequest(TOKEN_URL).statusCode()).isEqualTo(204);
        assertThat(addJob(second, job("Three")).statusCode()).isEqualTo(401);
        assertThat((Boolean) JsonPath.read(browser.get(TOKEN_URL).body(), "$.configured")).isFalse();
        assertThat(boardTitles(browser)).containsExactlyInAnyOrder("One", "Two");
    }

    @Test
    void theTokenOpensNothingElse() throws Exception {
        String token = generate(browser);

        for (String path : List.of("/api/board", "/api/settings", "/api/cv", "/api/auth/me", TOKEN_URL, "/api/jobs")) {
            assertThat(bearerGet(token, path).statusCode()).as(path).isEqualTo(401);
        }
        HttpResponse<String> browserEndpoint = bare.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/board/jobs"))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(job("Nope"))).build(), BodyHandlers.ofString());
        assertThat(browserEndpoint.statusCode()).isIn(401, 403);
        HttpResponse<String> revoke = bare.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN_URL))
                        .header("Authorization", "Bearer " + token).DELETE().build(), BodyHandlers.ofString());
        assertThat(revoke.statusCode()).isIn(401, 403);
        assertThat((Boolean) JsonPath.read(browser.get(TOKEN_URL).body(), "$.configured")).isTrue();
    }

    @Test
    void browserSessionsStillRequireCsrfAndTokenManagementRequiresALogin() throws Exception {
        TestApiClient fresh = new TestApiClient(port);
        assertThat(fresh.postJson(TOKEN_URL, "{}").statusCode()).isEqualTo(401);

        // Logged-in session without the X-XSRF-TOKEN header is still rejected (CSRF not globally disabled).
        java.net.CookieManager manager = new java.net.CookieManager();
        HttpClient session = HttpClient.newBuilder().cookieHandler(manager).build();
        String base = "http://localhost:" + port;
        session.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), BodyHandlers.ofString());
        String xsrf = manager.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
        HttpResponse<String> login = session.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", xsrf)
                .POST(BodyPublishers.ofString("email=test%40jobhunter.local&password=test-password-123")).build(), BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        HttpResponse<String> noCsrf = session.send(HttpRequest.newBuilder(URI.create(base + "/api/board/jobs"))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(job("No Csrf"))).build(), BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
    }
}
