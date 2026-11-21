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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

@ExtendWith(OutputCaptureExtension.class)
class McpIntegrationTokenIntegrationTest extends IntegrationTestBase {

    private static final String TOKENS_URL = "/api/settings/integrations/tokens";
    private static final String PASSWORD = "tokens-password-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testDataSupport;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private TestApiClient browser;
    private String email;
    private String company;

    /** A token as returned by create/regenerate. */
    private record Issued(long id, String token, String body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        // A fresh user per test: tokens accumulate per user, so list assertions stay exact.
        String run = String.valueOf(System.nanoTime());
        email = "tokens-" + run + "@jobhunter.local";
        browser = login(email);
        company = "Mcp Co " + run;
    }

    // ---------------------------------------------------------------- helpers

    private TestApiClient login(String userEmail) throws Exception {
        testDataSupport.createUserIfMissing(userEmail, PASSWORD);
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(userEmail, PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private Issued create(TestApiClient client, String bodyJson) throws Exception {
        HttpResponse<String> response = client.postJson(TOKENS_URL, bodyJson);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return issued(response.body());
    }

    private Issued create(TestApiClient client) throws Exception {
        return create(client, "{}");
    }

    private Issued regenerate(TestApiClient client, long id) throws Exception {
        HttpResponse<String> response = client.postJson(TOKENS_URL + "/" + id + "/regenerate", "{}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return issued(response.body());
    }

    private static Issued issued(String body) {
        return new Issued(JsonPath.<Number>read(body, "$.id").longValue(), JsonPath.read(body, "$.token"), body);
    }

    private String list(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.get(TOKENS_URL);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
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

    private int companiesStatus(String bearer) throws Exception {
        return bearerGet(bearer, "/api/integrations/companies").statusCode();
    }

    private String job(String title) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}";
    }

    private List<String> boardTitles(TestApiClient client) throws Exception {
        return JsonPath.read(client.get("/api/board").body(), "$[?(@.companyName=='" + company + "')].title");
    }

    private int tokenRows() {
        return testDataSupport.count("select count(*) from integration_tokens t join users u on u.id = t.user_id where u.email = ?", email);
    }

    // ---------------------------------------------------------------- secrets

    @Test
    void rawTokenIsReturnedOnlyOnCreationAndOnlyItsDigestIsStored() throws Exception {
        Issued issued = create(browser);
        String token = issued.token();

        assertThat(token).startsWith("jh_").hasSizeGreaterThan(40);
        assertThat(testDataSupport.count("select count(*) from integration_tokens where token_hash = ?", token)).isZero();
        assertThat(testDataSupport.count("""
                select count(*) from integration_tokens t join users u on u.id = t.user_id
                where u.email = ? and t.id = ? and t.token_hash = ?""", email, issued.id(), IntegrationTokenService.hash(token)))
                .isEqualTo(1);

        String list = list(browser);
        assertThat(list).doesNotContain(token).doesNotContain(IntegrationTokenService.hash(token))
                .doesNotContain("\"token\"").doesNotContain("hash");
        assertThat(JsonPath.<List<Integer>>read(list, "$[*].id")).containsExactly((int) issued.id());
        assertThat(JsonPath.<String>read(list, "$[0].suffix")).isEqualTo(token.substring(token.length() - 4));
        assertThat(JsonPath.<String>read(list, "$[0].createdAt")).isNotBlank();
        assertThat((Object) JsonPath.read(list, "$[0].name")).isNull();
        assertThat((Object) JsonPath.read(list, "$[0].regeneratedAt")).isNull();
    }

    @Test
    void secretsAndDigestsNeverReachTheLogs(CapturedOutput output) throws Exception {
        Issued issued = create(browser, "{\"name\":\"Logged?\"}");
        Issued regenerated = regenerate(browser, issued.id());
        assertThat(addJob(regenerated.token(), job("Log Role")).statusCode()).isEqualTo(201);
        assertThat(addJob(issued.token(), job("Log Role 2")).statusCode()).isEqualTo(401);
        assertThat(browser.deleteRequest(TOKENS_URL + "/" + issued.id()).statusCode()).isEqualTo(204);

        for (String secret : List.of(issued.token(), regenerated.token())) {
            assertThat(output.getAll()).doesNotContain(secret).doesNotContain(IntegrationTokenService.hash(secret));
        }
    }

    @Test
    void tokensNeverExpireOnTheirOwn() throws Exception {
        String token = create(browser).token();
        testDataSupport.update("update integration_tokens set created_at = now() - interval '3 years' where token_hash = ?",
                IntegrationTokenService.hash(token));

        assertThat(addJob(token, job("Old Token Role")).statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- multiple tokens and names

    @Test
    void severalTokensAreActiveAtTheSameTimeAndCreatingOneKeepsTheOthers() throws Exception {
        Issued claude = create(browser, "{\"name\":\"Claude Desktop\"}");
        Issued codex = create(browser, "{\"name\":\"Codex\"}");
        Issued unnamed = create(browser);

        assertThat(List.of(claude.token(), codex.token(), unnamed.token())).doesNotHaveDuplicates();
        assertThat(tokenRows()).isEqualTo(3);
        for (Issued issued : List.of(claude, codex, unnamed)) {
            assertThat(companiesStatus(issued.token())).isEqualTo(200);
        }
        assertThat(addJob(claude.token(), job("Via Claude")).statusCode()).isEqualTo(201);
        assertThat(addJob(codex.token(), job("Via Codex")).statusCode()).isEqualTo(201);
        assertThat(boardTitles(browser)).containsExactlyInAnyOrder("Via Claude", "Via Codex");

        // Listed oldest first, with names as stored.
        String list = list(browser);
        assertThat(JsonPath.<List<Integer>>read(list, "$[*].id"))
                .containsExactly((int) claude.id(), (int) codex.id(), (int) unnamed.id());
        assertThat(JsonPath.<List<String>>read(list, "$[*].name")).containsExactly("Claude Desktop", "Codex", null);
    }

    @Test
    void namesAreOptionalTrimmedNotUniqueAndBlankMeansUnnamed() throws Exception {
        assertThat(JsonPath.<String>read(create(browser, "{\"name\":\"  Codex \\t\"}").body(), "$.name")).isEqualTo("Codex");
        assertThat(JsonPath.<String>read(create(browser, "{\"name\":\"Codex\"}").body(), "$.name")).isEqualTo("Codex");
        assertThat((Object) JsonPath.read(create(browser, "{\"name\":\"   \"}").body(), "$.name")).isNull();
        assertThat((Object) JsonPath.read(create(browser, "{\"name\":null}").body(), "$.name")).isNull();
        // No body at all is an unnamed token too.
        HttpResponse<String> noBody = browser.send("POST", TOKENS_URL, null, null);
        assertThat(noBody.statusCode()).isEqualTo(201);
        assertThat((Object) JsonPath.read(noBody.body(), "$.name")).isNull();

        assertThat(testDataSupport.strings("""
                select coalesce(t.name, '<null>') from integration_tokens t join users u on u.id = t.user_id
                where u.email = ? order by t.id""", email))
                .containsExactly("Codex", "Codex", "<null>", "<null>", "<null>");
    }

    @Test
    void tooLongNamesAreRejectedWithoutCreatingOrChangingAnything() throws Exception {
        String tooLong = "x".repeat(101);
        HttpResponse<String> created = browser.postJson(TOKENS_URL, "{\"name\":\"" + tooLong + "\"}");
        assertThat(created.statusCode()).isEqualTo(400);
        assertThat(created.body()).contains("\"field\":\"name\"").doesNotContain("jh_");
        assertThat(tokenRows()).isZero();

        Issued issued = create(browser, "{\"name\":\"Keep\"}");
        assertThat(browser.patchJson(TOKENS_URL + "/" + issued.id(), "{\"name\":\"" + tooLong + "\"}").statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(list(browser), "$[*].name")).containsExactly("Keep");
        // Exactly 100 characters (after trimming) is fine.
        HttpResponse<String> max = browser.patchJson(TOKENS_URL + "/" + issued.id(), "{\"name\":\"  " + "y".repeat(100) + "  \"}");
        assertThat(max.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(max.body(), "$.name")).isEqualTo("y".repeat(100));
    }

    @Test
    void renamingAndClearingANameKeepTheSecretAndTheOtherTokens() throws Exception {
        Issued first = create(browser, "{\"name\":\"Claude Desktop\"}");
        Issued second = create(browser, "{\"name\":\"Codex\"}");
        String hashBefore = testDataSupport.strings("select token_hash from integration_tokens where id = ?", first.id()).getFirst();

        HttpResponse<String> renamed = browser.patchJson(TOKENS_URL + "/" + first.id(), "{\"name\":\"  Laptop Claude  \"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(renamed.body(), "$.name")).isEqualTo("Laptop Claude");
        assertThat(renamed.body()).doesNotContain("\"token\"").doesNotContain(hashBefore);
        assertThat(JsonPath.<String>read(renamed.body(), "$.suffix")).isEqualTo(JsonPath.<String>read(first.body(), "$.suffix"));

        HttpResponse<String> cleared = browser.patchJson(TOKENS_URL + "/" + first.id(), "{\"name\":\"   \"}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(cleared.body(), "$.name")).isNull();
        assertThat(testDataSupport.strings("select coalesce(name, '<null>') from integration_tokens where id = ?", first.id()))
                .containsExactly("<null>");
        assertThat(browser.patchJson(TOKENS_URL + "/" + first.id(), "{\"name\":\"Again\"}").statusCode()).isEqualTo(200);
        assertThat(browser.patchJson(TOKENS_URL + "/" + first.id(), "{\"name\":null}").statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(list(browser), "$[0].name")).isNull();

        // Same secret, same id, nothing else touched.
        assertThat(testDataSupport.strings("select token_hash from integration_tokens where id = ?", first.id()))
                .containsExactly(hashBefore);
        assertThat(companiesStatus(first.token())).isEqualTo(200);
        assertThat(companiesStatus(second.token())).isEqualTo(200);
        assertThat(JsonPath.<String>read(list(browser), "$[1].name")).isEqualTo("Codex");
    }

    // ---------------------------------------------------------------- revoke and regenerate

    @Test
    void revokingOneTokenLeavesTheOthersWorking() throws Exception {
        Issued claude = create(browser, "{\"name\":\"Claude Desktop\"}");
        Issued codex = create(browser, "{\"name\":\"Codex\"}");
        assertThat(addJob(claude.token(), job("One")).statusCode()).isEqualTo(201);

        assertThat(browser.deleteRequest(TOKENS_URL + "/" + claude.id()).statusCode()).isEqualTo(204);

        assertThat(addJob(claude.token(), job("Two")).statusCode()).isEqualTo(401);
        assertThat(companiesStatus(claude.token())).isEqualTo(401);
        assertThat(addJob(codex.token(), job("Two")).statusCode()).isEqualTo(201);
        assertThat(JsonPath.<List<Integer>>read(list(browser), "$[*].id")).containsExactly((int) codex.id());
        assertThat(boardTitles(browser)).containsExactlyInAnyOrder("One", "Two");

        // Already revoked: nothing left to act on.
        assertThat(browser.deleteRequest(TOKENS_URL + "/" + claude.id()).statusCode()).isEqualTo(404);
        assertThat(browser.postJson(TOKENS_URL + "/" + claude.id() + "/regenerate", "{}").statusCode()).isEqualTo(404);
        assertThat(browser.patchJson(TOKENS_URL + "/" + claude.id(), "{\"name\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(companiesStatus(codex.token())).isEqualTo(200);
    }

    @Test
    void regeneratingKeepsIdNameAndCreationAndInvalidatesOnlyThatTokensPreviousSecret() throws Exception {
        Issued claude = create(browser, "{\"name\":\"Claude Desktop\"}");
        Issued codex = create(browser, "{\"name\":\"Codex\"}");
        String createdAt = JsonPath.read(claude.body(), "$.createdAt");

        Issued regenerated = regenerate(browser, claude.id());

        assertThat(regenerated.id()).isEqualTo(claude.id());
        assertThat(regenerated.token()).startsWith("jh_").isNotEqualTo(claude.token());
        assertThat(JsonPath.<String>read(regenerated.body(), "$.name")).isEqualTo("Claude Desktop");
        assertThat(JsonPath.<String>read(regenerated.body(), "$.createdAt")).isEqualTo(createdAt);
        assertThat(JsonPath.<String>read(regenerated.body(), "$.regeneratedAt")).isNotBlank();
        assertThat(JsonPath.<String>read(regenerated.body(), "$.suffix"))
                .isEqualTo(regenerated.token().substring(regenerated.token().length() - 4));

        assertThat(addJob(claude.token(), job("Old Secret")).statusCode()).isEqualTo(401);
        assertThat(companiesStatus(claude.token())).isEqualTo(401);
        assertThat(addJob(regenerated.token(), job("New Secret")).statusCode()).isEqualTo(201);
        assertThat(addJob(codex.token(), job("Other Token")).statusCode()).isEqualTo(201);
        assertThat(boardTitles(browser)).containsExactlyInAnyOrder("New Secret", "Other Token");

        assertThat(tokenRows()).isEqualTo(2);
        assertThat(testDataSupport.strings("select token_hash from integration_tokens where id = ?", claude.id()))
                .containsExactly(IntegrationTokenService.hash(regenerated.token()));
        String list = list(browser);
        assertThat(JsonPath.<List<Integer>>read(list, "$[*].id")).containsExactly((int) claude.id(), (int) codex.id());
        assertThat(list).doesNotContain(regenerated.token());

        // Regenerating again invalidates the secret from the previous regeneration.
        Issued again = regenerate(browser, claude.id());
        assertThat(companiesStatus(regenerated.token())).isEqualTo(401);
        assertThat(companiesStatus(again.token())).isEqualTo(200);
        assertThat(companiesStatus(codex.token())).isEqualTo(200);
    }

    @Test
    void concurrentRegenerationsAndRenamesOfOneTokenLeaveExactlyOneValidSecret() throws Exception {
        Issued target = create(browser, "{\"name\":\"Start\"}");
        Issued other = create(browser, "{\"name\":\"Other\"}");
        int regenerations = 8;
        int renames = 4;
        ExecutorService pool = Executors.newFixedThreadPool(regenerations + renames);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> regenerateCalls = new ArrayList<>();
            List<Future<HttpResponse<String>>> renameCalls = new ArrayList<>();
            for (int i = 0; i < regenerations; i++) {
                regenerateCalls.add(pool.submit(awaiting(start, () ->
                        browser.postJson(TOKENS_URL + "/" + target.id() + "/regenerate", "{}"))));
            }
            for (int i = 0; i < renames; i++) {
                String name = "Name " + i;
                renameCalls.add(pool.submit(awaiting(start, () ->
                        browser.patchJson(TOKENS_URL + "/" + target.id(), "{\"name\":\"" + name + "\"}"))));
            }
            start.countDown();

            List<String> secrets = new ArrayList<>();
            for (Future<HttpResponse<String>> call : regenerateCalls) {
                HttpResponse<String> response = call.get();
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                secrets.add(JsonPath.read(response.body(), "$.token"));
            }
            for (Future<HttpResponse<String>> call : renameCalls) {
                assertThat(call.get().statusCode()).isEqualTo(200);
            }

            // Serialized per token: every call succeeded, the secrets differ, and only the last committed one works.
            assertThat(secrets).doesNotHaveDuplicates();
            List<String> valid = new ArrayList<>();
            for (String secret : secrets) {
                if (companiesStatus(secret) == 200) {
                    valid.add(secret);
                }
            }
            assertThat(valid).hasSize(1);
            assertThat(companiesStatus(target.token())).isEqualTo(401);
            assertThat(testDataSupport.strings("select token_hash from integration_tokens where id = ?", target.id()))
                    .containsExactly(IntegrationTokenService.hash(valid.getFirst()));
            // A rename never brought an old secret back, and one of the renames is the final name.
            assertThat(JsonPath.<String>read(list(browser), "$[0].name")).matches("Name [0-3]");
            assertThat(companiesStatus(other.token())).isEqualTo(200);
            assertThat(tokenRows()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Callable<T> awaiting(CountDownLatch start, Callable<T> call) {
        return () -> {
            start.await();
            return call.call();
        };
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void anotherUserCanNeitherSeeNorChangeMyTokens() throws Exception {
        Issued mine = create(browser, "{\"name\":\"Mine\"}");
        TestApiClient other = login("tokens-other-" + System.nanoTime() + "@jobhunter.local");
        Issued theirs = create(other, "{\"name\":\"Theirs\"}");
        String path = TOKENS_URL + "/" + mine.id();

        assertThat(list(other)).doesNotContain("Mine");
        assertThat(JsonPath.<List<Integer>>read(list(other), "$[*].id")).containsExactly((int) theirs.id());
        assertThat(other.patchJson(path, "{\"name\":\"Hijacked\"}").statusCode()).isEqualTo(404);
        assertThat(other.postJson(path + "/regenerate", "{}").statusCode()).isEqualTo(404);
        assertThat(other.deleteRequest(path).statusCode()).isEqualTo(404);
        // Same answer as an id that does not exist at all.
        assertThat(other.deleteRequest(TOKENS_URL + "/999999999").statusCode()).isEqualTo(404);

        assertThat(JsonPath.<List<String>>read(list(browser), "$[*].name")).containsExactly("Mine");
        assertThat(testDataSupport.strings("select token_hash from integration_tokens where id = ?", mine.id()))
                .containsExactly(IntegrationTokenService.hash(mine.token()));
        assertThat(companiesStatus(mine.token())).isEqualTo(200);
        assertThat(companiesStatus(theirs.token())).isEqualTo(200);
    }

    @Test
    void eachTokenActsOnlyForItsOwnUserEvenIfTheBodyNamesAnotherUser() throws Exception {
        TestApiClient other = login("tokens-owner-" + System.nanoTime() + "@jobhunter.local");
        String token = create(browser).token();
        String secondToken = create(browser).token();

        HttpResponse<String> added = addJob(token, """
                {"title":"Sneaky","companyName":"%s","userId":999999,"email":"someone@else","owner":"x"}""".formatted(company));
        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(addJob(secondToken, job("Second")).statusCode()).isEqualTo(201);
        assertThat(boardTitles(browser)).containsExactlyInAnyOrder("Sneaky", "Second");
        assertThat(boardTitles(other)).isEmpty();

        String otherToken = create(other).token();
        assertThat(addJob(otherToken, job("Other Role")).statusCode()).isEqualTo(201);
        assertThat(boardTitles(other)).containsExactly("Other Role");
        assertThat(boardTitles(browser)).doesNotContain("Other Role");
    }

    // ---------------------------------------------------------------- unchanged integration behaviour

    @Test
    void aTokenStoredBeforeTheUpgradeKeepsWorkingAsAnUnnamedToken() throws Exception {
        // Exactly the row the single-token version created (no name, never regenerated).
        String legacy = "jh_legacy_" + System.nanoTime() + "_abcdefghijklmnopqrstuvwxyz0123";
        testDataSupport.update("insert into integration_tokens (user_id, token_hash, token_suffix, created_at) "
                        + "select id, ?, ?, now() - interval '30 days' from users where email = ?",
                IntegrationTokenService.hash(legacy), legacy.substring(legacy.length() - 4), email);

        assertThat(addJob(legacy, job("Legacy Role")).statusCode()).isEqualTo(201);
        String list = list(browser);
        assertThat((Object) JsonPath.read(list, "$[0].name")).isNull();
        assertThat(JsonPath.<String>read(list, "$[0].suffix")).isEqualTo("0123");

        // A new token next to it keeps the legacy one working.
        Issued added = create(browser, "{\"name\":\"Codex\"}");
        assertThat(companiesStatus(legacy)).isEqualTo(200);
        assertThat(companiesStatus(added.token())).isEqualTo(200);
    }

    @Test
    void addJobUsesTheSharedLogicAndLandsOnTheTokenOwnersBoard() throws Exception {
        String token = create(browser).token();

        HttpResponse<String> added = addJob(token, """
                {"title":"Backend Engineer","companyName":"%s","location":"Remote","workModel":"Remote",
                 "url":"https://example.com/jobs/1","salaryMin":100,"salaryMax":200,"currency":"USD",
                 "description":"Build things"}""".formatted(company));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(added.body(), "$.created")).isTrue();
        assertThat(JsonPath.<String>read(added.body(), "$.statusCode")).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(added.body(), "$.companyName")).isEqualTo(company);
        assertThat(added.body()).doesNotContain("userId").doesNotContain(email);
        assertThat(boardTitles(browser)).containsExactly("Backend Engineer");

        // Same ticket as the Board's Add Job button: re-adding through the browser endpoint is the idempotent 200.
        HttpResponse<String> viaBrowser = browser.postJson("/api/board/jobs", job("Backend Engineer"));
        assertThat(viaBrowser.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(viaBrowser.body(), "$.id")).isEqualTo(JsonPath.<Integer>read(added.body(), "$.applicationId"));
    }

    @Test
    void duplicatesCreateNothingNewWhicheverTokenIsUsed() throws Exception {
        String first = create(browser).token();
        String second = create(browser).token();
        HttpResponse<String> one = addJob(first, job("Dup Role"));
        HttpResponse<String> two = addJob(second, job("  dup   ROLE "));

        assertThat(one.statusCode()).isEqualTo(201);
        assertThat(two.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(two.body(), "$.created")).isFalse();
        assertThat(JsonPath.<Integer>read(two.body(), "$.applicationId")).isEqualTo(JsonPath.<Integer>read(one.body(), "$.applicationId"));
        assertThat(boardTitles(browser)).hasSize(1);
    }

    @Test
    void existingAddJobValidationApplies() throws Exception {
        String token = create(browser).token();

        assertThat(addJob(token, "{\"companyName\":\"" + company + "\"}").statusCode()).isEqualTo(400);
        assertThat(addJob(token, "{\"title\":\"X\",\"url\":\"ftp://nope\"}").statusCode()).isEqualTo(400);
        assertThat(addJob(token, "{\"title\":\"X\",\"salaryMin\":-1}").statusCode()).isEqualTo(400);
        assertThat(boardTitles(browser)).isEmpty();
    }

    @Test
    void missingInvalidAndSessionOnlyCredentialsAreRejected() throws Exception {
        create(browser);

        assertThat(addJob(null, job("A")).statusCode()).isEqualTo(401);
        assertThat(addJob("jh_not-a-real-token", job("A")).statusCode()).isEqualTo(401);
        assertThat(addJob("not-even-prefixed", job("A")).statusCode()).isEqualTo(401);
        // A logged-in browser session must not authenticate this endpoint either.
        assertThat(browser.postJson("/api/integrations/jobs", job("A")).statusCode()).isEqualTo(401);
        assertThat(boardTitles(browser)).isEmpty();
    }

    @Test
    void theTokenOpensNothingElseAndCannotManageTokens() throws Exception {
        Issued issued = create(browser);
        String token = issued.token();

        for (String path : List.of("/api/board", "/api/settings", "/api/cv", "/api/auth/me", TOKENS_URL, "/api/jobs")) {
            assertThat(bearerGet(token, path).statusCode()).as(path).isEqualTo(401);
        }
        HttpResponse<String> browserEndpoint = bare.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/board/jobs"))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(job("Nope"))).build(), BodyHandlers.ofString());
        assertThat(browserEndpoint.statusCode()).isIn(401, 403);
        for (HttpRequest.Builder request : List.of(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKENS_URL + "/" + issued.id())).DELETE(),
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKENS_URL + "/" + issued.id() + "/regenerate"))
                        .POST(BodyPublishers.noBody()),
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKENS_URL)).POST(BodyPublishers.noBody()))) {
            assertThat(bare.send(request.header("Authorization", "Bearer " + token).build(), BodyHandlers.ofString()).statusCode())
                    .isIn(401, 403);
        }
        assertThat(tokenRows()).isEqualTo(1);
        assertThat(companiesStatus(token)).isEqualTo(200);
    }

    @Test
    void tokenManagementRequiresALoginAndTheCsrfToken() throws Exception {
        TestApiClient fresh = new TestApiClient(port);
        fresh.get("/api/auth/csrf");
        assertThat(fresh.postJson(TOKENS_URL, "{}").statusCode()).isEqualTo(401);
        assertThat(fresh.get(TOKENS_URL).statusCode()).isEqualTo(401);

        Issued issued = create(browser);
        assertThat(browser.postJsonWithoutCsrfHeader(TOKENS_URL, "{}").statusCode()).isEqualTo(403);
        assertThat(browser.postJsonWithoutCsrfHeader(TOKENS_URL + "/" + issued.id() + "/regenerate", "{}").statusCode()).isEqualTo(403);
        assertThat(tokenRows()).isEqualTo(1);
        assertThat(companiesStatus(issued.token())).isEqualTo(200);
    }

    @Test
    void theSingleTokenEndpointIsGone() throws Exception {
        create(browser);

        // Its POST used to replace and its DELETE used to revoke "the" token; neither may touch the user's tokens now.
        assertThat(browser.postJson("/api/settings/integrations/mcp-token", "{}").statusCode()).isEqualTo(404);
        assertThat(browser.deleteRequest("/api/settings/integrations/mcp-token").statusCode()).isEqualTo(404);
        assertThat(tokenRows()).isEqualTo(1);
    }
}
