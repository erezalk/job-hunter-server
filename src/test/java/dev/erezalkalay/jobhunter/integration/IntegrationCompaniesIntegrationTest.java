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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * GET /api/integrations/companies: bearer-token-only, read-only, owner-scoped.
 * Every test uses freshly created users so the exact list can be asserted even
 * though the Testcontainers database is shared across the whole run.
 */
class IntegrationCompaniesIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "companies-password-123";
    private static final String COMPANIES_URL = "/api/integrations/companies";
    private static final String TOKEN_URL = "/api/settings/integrations/mcp-token";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testDataSupport;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private String run;

    @BeforeEach
    void setUp() {
        run = String.valueOf(System.nanoTime());
    }

    private TestApiClient newUser(String name) throws Exception {
        String email = name + "-" + run + "@jobhunter.local";
        testDataSupport.createUserIfMissing(email, PASSWORD);
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(email, PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private String generateToken(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.postJson(TOKEN_URL, "{}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }

    private HttpResponse<String> getCompanies(String bearer, String pathAndQuery) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return bare.send(request.build(), BodyHandlers.ofString());
    }

    private List<String> companyNames(String bearer) throws Exception {
        HttpResponse<String> response = getCompanies(bearer, COMPANIES_URL);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.companies[*].name");
    }

    private long addJob(TestApiClient client, String title, String companyName) throws Exception {
        String company = companyName == null ? "" : ",\"companyName\":\"" + companyName + "\"";
        HttpResponse<String> response = client.postJson("/api/board/jobs", "{\"title\":\"" + title + "\"" + company + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    private void setStatus(TestApiClient client, long applicationId, String status) throws Exception {
        assertThat(client.patchJson("/api/applications/" + applicationId + "/status",
                "{\"statusCode\":\"" + status + "\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void returnsEachOfTheTokenOwnersCompaniesOnceInCaseInsensitiveOrderWhateverTheStatus() throws Exception {
        TestApiClient alice = newUser("alice");
        long rejected = addJob(alice, "Rejected Role", "zeta Corp");
        addJob(alice, "Second Role", "zeta Corp");
        long archived = addJob(alice, "Archived Role", "Acme");
        long lowMatch = addJob(alice, "Low Role", "beta Inc");
        addJob(alice, "Active Role", "Delta");
        addJob(alice, "No Company Role", null);
        setStatus(alice, rejected, "REJECTED");
        setStatus(alice, archived, "ARCHIVED");
        setStatus(alice, lowMatch, "LOW_MATCH");

        String token = generateToken(alice);

        assertThat(companyNames(token)).containsExactly("Acme", "beta Inc", "Delta", "zeta Corp");
    }

    @Test
    void returnsNothingButNamesAndNoIdsOrInternalFields() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Role", "Acme " + run);
        String token = generateToken(alice);

        HttpResponse<String> response = getCompanies(token, COMPANIES_URL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("companies");
        List<Map<String, Object>> companies = JsonPath.read(response.body(), "$.companies");
        assertThat(companies).hasSize(1);
        assertThat(companies.get(0)).containsOnlyKeys("name");
        assertThat(response.body()).doesNotContain("\"id\"", "\"user", "email", "website", "notes", "nameKey", "createdAt");
    }

    @Test
    void anAccountWithoutCompaniesGetsAnEmptyList() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Companyless Role", null);
        String token = generateToken(alice);

        HttpResponse<String> response = getCompanies(token, COMPANIES_URL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.companies")).isEmpty();
    }

    @Test
    void neverShowsAnotherUsersCompaniesAndTheOwnerCannotBeChosen() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        addJob(alice, "A1", "Alice Only " + run);
        addJob(alice, "A2", "Shared Name " + run);
        addJob(bob, "B1", "Bob Only " + run);
        addJob(bob, "B2", "Shared Name " + run);
        String aliceToken = generateToken(alice);
        String bobToken = generateToken(bob);

        assertThat(companyNames(aliceToken)).containsExactly("Alice Only " + run, "Shared Name " + run);
        assertThat(companyNames(bobToken)).containsExactly("Bob Only " + run, "Shared Name " + run);

        // Nothing in the request can redirect the lookup to somebody else.
        long bobId = testDataSupport.userId("bob-" + run + "@jobhunter.local");
        for (String query : List.of("?userId=" + bobId, "?email=bob-" + run + "%40jobhunter.local", "?owner=bob")) {
            HttpResponse<String> response = getCompanies(aliceToken, COMPANIES_URL + query);
            assertThat(response.statusCode()).as(query).isEqualTo(200);
            assertThat(response.body()).as(query).doesNotContain("Bob Only");
        }
    }

    @Test
    void missingInvalidAndSessionOnlyCredentialsAreRejected() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Role", "Secret Co " + run);
        String token = generateToken(alice);

        for (HttpResponse<String> response : List.of(
                getCompanies(null, COMPANIES_URL),
                getCompanies("jh_not-a-real-token", COMPANIES_URL),
                getCompanies("not-even-prefixed", COMPANIES_URL),
                alice.get(COMPANIES_URL))) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain("Secret Co");
        }
        // A browser session (and its CSRF cookie) must not authenticate this endpoint, bearer header or not.
        assertThat(companyNames(token)).containsExactly("Secret Co " + run);
    }

    @Test
    void replacedAndRevokedTokensStopWorkingImmediately() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Role", "Acme " + run);
        String first = generateToken(alice);
        assertThat(companyNames(first)).containsExactly("Acme " + run);

        String second = generateToken(alice);
        assertThat(getCompanies(first, COMPANIES_URL).statusCode()).isEqualTo(401);
        assertThat(companyNames(second)).containsExactly("Acme " + run);

        assertThat(alice.deleteRequest(TOKEN_URL).statusCode()).isEqualTo(204);
        assertThat(getCompanies(second, COMPANIES_URL).statusCode()).isEqualTo(401);
    }

    @Test
    void aCompanyDisappearsOnceItsLastJobIsDeleted() throws Exception {
        TestApiClient alice = newUser("alice");
        long gone = addJob(alice, "Gone Role", "Gone Co " + run);
        addJob(alice, "Kept Role", "Kept Co " + run);
        String token = generateToken(alice);
        assertThat(companyNames(token)).containsExactly("Gone Co " + run, "Kept Co " + run);

        assertThat(alice.deleteRequest("/api/applications/" + gone).statusCode()).isEqualTo(204);

        assertThat(companyNames(token)).containsExactly("Kept Co " + run);
    }

    @Test
    void readingChangesNothing() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Role", "Acme " + run);
        setStatus(alice, addJob(alice, "Rejected Role", "Reject Co " + run), "REJECTED");
        String token = generateToken(alice);

        String snapshotSql = """
                select (select count(*) from companies) || '/' || (select count(*) from jobs) || '/'
                    || (select count(*) from applications) || '/' || (select count(*) from application_status_history) || '/'
                    || coalesce((select max(updated_at)::text from companies), '') || '/'
                    || coalesce((select max(updated_at)::text from jobs), '') || '/'
                    || coalesce((select max(updated_at)::text from applications), '')""";
        List<String> before = testDataSupport.strings(snapshotSql);

        assertThat(companyNames(token)).hasSize(2);
        assertThat(companyNames(token)).hasSize(2);

        assertThat(testDataSupport.strings(snapshotSql)).isEqualTo(before);
    }

    @Test
    void theEndpointIsReadOnly() throws Exception {
        TestApiClient alice = newUser("alice");
        String token = generateToken(alice);

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            HttpResponse<String> response = bare.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + COMPANIES_URL))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(method, BodyPublishers.ofString("{\"companies\":[{\"name\":\"Injected\"}]}")).build(), BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method).isGreaterThanOrEqualTo(400);
        }
        assertThat(companyNames(token)).isEmpty();
    }
}
