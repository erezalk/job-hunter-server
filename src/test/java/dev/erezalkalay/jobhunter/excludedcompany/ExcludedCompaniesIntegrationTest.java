package dev.erezalkalay.jobhunter.excludedcompany;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Settings → Job Discovery → Excluded Companies (session API) and its read-only
 * bearer-token view for MCP clients. Every test uses freshly created users so
 * exact lists can be asserted on the shared Testcontainers database.
 */
class ExcludedCompaniesIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "excluded-password-123";
    private static final String URL = "/api/settings/excluded-companies";
    private static final String INTEGRATION_URL = "/api/integrations/excluded-companies";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private String run;

    @BeforeEach
    void setUp() {
        run = String.valueOf(System.nanoTime());
    }

    // --- CRUD ----------------------------------------------------------------

    @Test
    void aNewUserHasNoExcludedCompanies() throws Exception {
        TestApiClient alice = newUser("alice");

        HttpResponse<String> response = alice.get(URL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$")).isEmpty();
    }

    @Test
    void addsTrimmedNamesAndListsThemInCaseInsensitiveOrder() throws Exception {
        TestApiClient alice = newUser("alice");

        HttpResponse<String> created = add(alice, "  PPRO  ");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.name")).isEqualTo("PPRO");
        assertThat(JsonPath.<Number>read(created.body(), "$.id")).isNotNull();
        assertThat(add(alice, "flix").statusCode()).isEqualTo(201);
        assertThat(add(alice, "Example   \t Company").statusCode()).isEqualTo(201);

        assertThat(names(alice)).containsExactly("Example Company", "flix", "PPRO");
    }

    @Test
    void renamesAnEntryInPlace() throws Exception {
        TestApiClient alice = newUser("alice");
        long id = idOf(add(alice, "Flix"));

        HttpResponse<String> renamed = alice.patchJson(URL + "/" + id, body("  FlixBus  "));

        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(renamed.body(), "$.id").longValue()).isEqualTo(id);
        assertThat(JsonPath.<String>read(renamed.body(), "$.name")).isEqualTo("FlixBus");
        assertThat(names(alice)).containsExactly("FlixBus");
    }

    @Test
    void changingOnlyTheCaseOfAnEntrysOwnNameIsAllowed() throws Exception {
        TestApiClient alice = newUser("alice");
        long id = idOf(add(alice, "ppro"));

        assertThat(alice.patchJson(URL + "/" + id, body("PPRO")).statusCode()).isEqualTo(200);
        assertThat(alice.patchJson(URL + "/" + id, body("PPRO")).statusCode()).isEqualTo(200);

        assertThat(names(alice)).containsExactly("PPRO");
    }

    @Test
    void removesAnEntryAndOnlyThatEntry() throws Exception {
        TestApiClient alice = newUser("alice");
        long flix = idOf(add(alice, "Flix"));
        add(alice, "PPRO");

        assertThat(alice.deleteRequest(URL + "/" + flix).statusCode()).isEqualTo(204);

        assertThat(names(alice)).containsExactly("PPRO");
        assertThat(alice.deleteRequest(URL + "/" + flix).statusCode()).isEqualTo(404);
        assertThat(alice.patchJson(URL + "/" + flix, body("Flix")).statusCode()).isEqualTo(404);
        // A removed name can be added again.
        assertThat(add(alice, "flix").statusCode()).isEqualTo(201);
    }

    // --- Validation and duplicates -------------------------------------------

    @Test
    void rejectsAnEmptyNameWithAFieldError() throws Exception {
        TestApiClient alice = newUser("alice");
        long id = idOf(add(alice, "Flix"));

        for (String json : List.of(body(""), body("   "), body(" \t\n "), "{\"name\":null}", "{}")) {
            HttpResponse<String> response = alice.postJson(URL, json);
            assertThat(response.statusCode()).as(json).isEqualTo(400);
            assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].field")).isEqualTo("name");
            assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].message")).isEqualTo("Company name must not be empty");

            assertThat(alice.patchJson(URL + "/" + id, json).statusCode()).as("rename " + json).isEqualTo(400);
        }
        assertThat(names(alice)).containsExactly("Flix");
    }

    @Test
    void rejectsATooLongNameAndAMalformedBody() throws Exception {
        TestApiClient alice = newUser("alice");

        HttpResponse<String> tooLong = add(alice, "x".repeat(256));
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tooLong.body(), "$.fieldErrors[0].field")).isEqualTo("name");
        assertThat(add(alice, "x".repeat(255)).statusCode()).isEqualTo(201);

        assertThat(alice.postJson(URL, "not json").statusCode()).isEqualTo(400);
        assertThat(alice.postJson(URL, "{\"name\":[\"a\"]}").statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsADuplicateIgnoringCaseAndSurroundingWhitespace() throws Exception {
        TestApiClient alice = newUser("alice");
        add(alice, "Flix");

        for (String duplicate : List.of("Flix", "flix", "  FLIX ", "fLiX")) {
            HttpResponse<String> response = add(alice, duplicate);
            assertThat(response.statusCode()).as(duplicate).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo("\"Flix\" is already in your excluded companies");
            assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].field")).isEqualTo("name");
        }
        assertThat(names(alice)).containsExactly("Flix");
    }

    @Test
    void rejectsRenamingToAnotherEntrysNameIgnoringCase() throws Exception {
        TestApiClient alice = newUser("alice");
        add(alice, "Flix");
        long ppro = idOf(add(alice, "PPRO"));

        HttpResponse<String> response = alice.patchJson(URL + "/" + ppro, body(" FLIX "));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].field")).isEqualTo("name");
        assertThat(names(alice)).containsExactly("Flix", "PPRO");
    }

    @Test
    void theDatabaseItselfRefusesADuplicateIgnoringCaseAndAnUntrimmedOrEmptyName() throws Exception {
        TestApiClient alice = newUser("alice");
        add(alice, "Flix");
        long userId = testData.userId(email("alice"));

        assertThatThrownBy(() -> testData.update("INSERT INTO excluded_companies (user_id, name) VALUES (?, 'FLIX')", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_excluded_companies_user_name_ignore_case");
        assertThatThrownBy(() -> testData.update("INSERT INTO excluded_companies (user_id, name) VALUES (?, ' Padded ')", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_excluded_companies_name_trimmed");
        assertThatThrownBy(() -> testData.update("INSERT INTO excluded_companies (user_id, name) VALUES (?, '')", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_excluded_companies_name_trimmed");
        assertThat(names(alice)).containsExactly("Flix");
    }

    // --- Authentication and user isolation -----------------------------------

    @Test
    void requiresALoggedInSession() throws Exception {
        TestApiClient anonymous = new TestApiClient(port);
        anonymous.get("/api/auth/csrf");

        assertThat(anonymous.get(URL).statusCode()).isEqualTo(401);
        assertThat(anonymous.postJson(URL, body("Flix")).statusCode()).isEqualTo(401);
        assertThat(anonymous.patchJson(URL + "/1", body("Flix")).statusCode()).isEqualTo(401);
        assertThat(anonymous.deleteRequest(URL + "/1").statusCode()).isEqualTo(401);
    }

    @Test
    void anIntegrationTokenCannotEditTheList() throws Exception {
        TestApiClient alice = newUser("alice");
        long id = idOf(add(alice, "Flix"));
        String token = generateToken(alice);

        // The session endpoints do not accept a bearer token...
        assertThat(bearer("GET", URL, token, null).statusCode()).isEqualTo(401);
        assertThat(bearer("POST", URL, token, body("PPRO")).statusCode()).isIn(401, 403);
        assertThat(bearer("PATCH", URL + "/" + id, token, body("PPRO")).statusCode()).isIn(401, 403);
        assertThat(bearer("DELETE", URL + "/" + id, token, null).statusCode()).isIn(401, 403);
        // ...and the integration endpoint is read-only.
        assertThat(bearer("POST", INTEGRATION_URL, token, body("PPRO")).statusCode()).isEqualTo(405);
        assertThat(bearer("PUT", INTEGRATION_URL, token, "[\"PPRO\"]").statusCode()).isEqualTo(405);
        assertThat(bearer("DELETE", INTEGRATION_URL, token, null).statusCode()).isEqualTo(405);

        assertThat(names(alice)).containsExactly("Flix");
    }

    @Test
    void eachUserOnlySeesAndChangesTheirOwnList() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        long aliceFlix = idOf(add(alice, "Flix"));
        add(alice, "PPRO");

        assertThat(names(bob)).isEmpty();
        // The same name is independent per user.
        assertThat(add(bob, "flix").statusCode()).isEqualTo(201);
        // Another user's id behaves exactly like a missing one.
        assertThat(bob.patchJson(URL + "/" + aliceFlix, body("Hacked")).statusCode()).isEqualTo(404);
        assertThat(bob.deleteRequest(URL + "/" + aliceFlix).statusCode()).isEqualTo(404);

        assertThat(names(alice)).containsExactly("Flix", "PPRO");
        assertThat(names(bob)).containsExactly("flix");
    }

    @Test
    void deletingAUserRemovesTheirListOnly() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        add(alice, "Flix");
        add(bob, "Flix");
        long aliceId = testData.userId(email("alice"));

        testData.update("DELETE FROM users WHERE id = ?", aliceId);

        assertThat(testData.count("SELECT count(*) FROM excluded_companies WHERE user_id = ?", aliceId)).isZero();
        assertThat(names(bob)).containsExactly("Flix");
    }

    // --- Integration (MCP) endpoint ------------------------------------------

    @Test
    void theIntegrationEndpointReturnsTheTokenOwnersNamesAsAPlainArray() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        add(alice, "PPRO");
        add(alice, "Flix");
        add(alice, "Example Company");
        add(bob, "Bob Only GmbH");
        String aliceToken = generateToken(alice);
        String bobToken = generateToken(bob);

        HttpResponse<String> response = bearer("GET", INTEGRATION_URL, aliceToken, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[\"Example Company\",\"Flix\",\"PPRO\"]");
        assertThat(bearer("GET", INTEGRATION_URL, bobToken, null).body()).isEqualTo("[\"Bob Only GmbH\"]");
        // Query parameters cannot select another user.
        assertThat(bearer("GET", INTEGRATION_URL + "?userId=" + testData.userId(email("bob")), aliceToken, null).body())
                .isEqualTo(response.body());
    }

    @Test
    void theIntegrationEndpointReturnsAnEmptyArrayWhenNothingIsExcluded() throws Exception {
        String token = generateToken(newUser("alice"));

        HttpResponse<String> response = bearer("GET", INTEGRATION_URL, token, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void theIntegrationEndpointRequiresAValidToken() throws Exception {
        TestApiClient alice = newUser("alice");
        add(alice, "Flix");
        String token = generateToken(alice);
        long tokenId = JsonPath.<Number>read(alice.get("/api/settings/integrations/tokens").body(), "$[0].id").longValue();

        assertThat(bearer("GET", INTEGRATION_URL, null, null).statusCode()).isEqualTo(401);
        assertThat(bearer("GET", INTEGRATION_URL, "jh_not-a-real-token", null).statusCode()).isEqualTo(401);
        // A logged-in browser session is not enough on the integration chain.
        assertThat(alice.get(INTEGRATION_URL).statusCode()).isEqualTo(401);

        assertThat(alice.deleteRequest("/api/settings/integrations/tokens/" + tokenId).statusCode()).isEqualTo(204);
        assertThat(bearer("GET", INTEGRATION_URL, token, null).statusCode()).isEqualTo(401);
    }

    // --- Independence from the Board -----------------------------------------

    @Test
    void excludingACompanyNeverChangesOrBlocksJobs() throws Exception {
        TestApiClient alice = newUser("alice");
        addJob(alice, "Backend Engineer", "Flix");
        String token = generateToken(alice);
        String boardBefore = alice.get("/api/board").body();
        String companiesBefore = bearer("GET", "/api/integrations/companies", token, null).body();

        long id = idOf(add(alice, "flix"));
        assertThat(alice.get("/api/board").body()).isEqualTo(boardBefore);
        assertThat(bearer("GET", "/api/integrations/companies", token, null).body()).isEqualTo(companiesBefore);

        // Jobs at an excluded company can still be added, manually and through the integration.
        addJob(alice, "Platform Engineer", "Flix");
        HttpResponse<String> viaIntegration = bearer("POST", "/api/integrations/jobs", token,
                "{\"title\":\"Data Engineer\",\"companyName\":\"FLIX\"}");
        assertThat(viaIntegration.statusCode()).isEqualTo(201);
        assertThat(bearer("GET", "/api/integrations/companies", token, null).body())
                .contains("Backend Engineer", "Platform Engineer", "Data Engineer");

        String boardAfterAdding = alice.get("/api/board").body();
        assertThat(alice.patchJson(URL + "/" + id, body("Flix SE")).statusCode()).isEqualTo(200);
        assertThat(alice.deleteRequest(URL + "/" + id).statusCode()).isEqualTo(204);
        assertThat(alice.get("/api/board").body()).isEqualTo(boardAfterAdding);
    }

    @Test
    void theListIsNeverFilledFromTheBoard() throws Exception {
        TestApiClient alice = newUser("alice");
        long rejected = addJob(alice, "Rejected Role", "Rejected Co");
        assertThat(alice.patchJson("/api/applications/" + rejected + "/status", "{\"statusCode\":\"REJECTED\"}").statusCode())
                .isEqualTo(200);
        addJob(alice, "Active Role", "Active Co");

        assertThat(names(alice)).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private String email(String name) {
        return name + "-excluded-" + run + "@jobhunter.local";
    }

    private TestApiClient newUser(String name) throws Exception {
        testData.createUserIfMissing(email(name), PASSWORD);
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(email(name), PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private static String body(String name) {
        return "{\"name\":" + TestApiClient.jsonString(name) + "}";
    }

    private static HttpResponse<String> add(TestApiClient client, String name) throws Exception {
        return client.postJson(URL, body(name));
    }

    private static long idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    private static List<String> names(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.get(URL);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$[*].name");
    }

    private static String generateToken(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.postJson("/api/settings/integrations/tokens", "{}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private static long addJob(TestApiClient client, String title, String company) throws Exception {
        HttpResponse<String> response = client.postJson("/api/board/jobs",
                "{\"title\":" + TestApiClient.jsonString(title) + ",\"companyName\":" + TestApiClient.jsonString(company) + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    private HttpResponse<String> bearer(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return bare.send(request.build(), BodyHandlers.ofString());
    }
}
