package dev.erezalkalay.jobhunter.auth;

import static dev.erezalkalay.jobhunter.support.TestApiClient.jsonString;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.settings.Settings;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/** POST /api/auth/register over real HTTP: session + CSRF cookies, real database. */
@ExtendWith(OutputCaptureExtension.class)
class RegistrationIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Sign-up pa$$word \"quoted\" 1";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private String run;

    @BeforeEach
    void setUp() {
        run = System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    // ===================== Success =====================

    @Test
    void signUpCreatesAnAccountAndLogsItInWithAFreshSessionAndCsrfToken(CapturedOutput output) throws Exception {
        String email = "new-" + run + "@example.com";
        TestApiClient api = new TestApiClient(port);
        // An anonymous request to a protected page leaves a session behind (saved request);
        // the login must not keep using it (session fixation).
        api.get("/api/board");
        String sessionBefore = api.cookie("JSESSIONID");
        api.get("/api/auth/csrf");
        String csrfBefore = api.cookie("XSRF-TOKEN");

        HttpResponse<String> response = api.register(email, PASSWORD, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.email")).isEqualTo(email);
        assertThat((Object) JsonPath.read(response.body(), "$.fullName")).isNull();
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$")).containsOnlyKeys("id", "email", "fullName");
        assertThat(response.body()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password").doesNotContain("$2");

        assertThat(api.cookie("JSESSIONID")).isNotNull().isNotEqualTo(sessionBefore);
        assertThat(api.cookie("XSRF-TOKEN")).isNotNull().isNotEqualTo(csrfBefore);
        HttpResponse<String> me = api.get("/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo(email);
        // The fresh CSRF token works for the next mutating request straight away.
        assertThat(api.postJson("/api/board/jobs", "{\"title\":\"First job\",\"companyName\":\"First Co\"}").statusCode())
                .isEqualTo(201);

        // Stored hashed with the application's BCrypt encoder; never the plaintext.
        String hash = testData.strings("SELECT password_hash FROM users WHERE email = ?", email).getFirst();
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(hash);
    }

    @Test
    void theNewAccountCanLogInLaterWithAnyCaseAndSurroundingWhitespaceInTheEmail() throws Exception {
        String email = "Login.Later+" + run + "@Example.com";
        assertThat(new TestApiClient(port).register(email, PASSWORD, PASSWORD).statusCode()).isEqualTo(201);

        assertThat(new TestApiClient(port).login(email, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(new TestApiClient(port).login("  " + email.toLowerCase() + " ", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(new TestApiClient(port).login(email, PASSWORD + "x").statusCode()).isEqualTo(401);
    }

    @Test
    void newAccountStartsWithDefaultSettingsAndNothingElse() throws Exception {
        TestApiClient api = new TestApiClient(port);
        String email = "empty-" + run + "@example.com";
        assertThat(api.register(email, PASSWORD, PASSWORD).statusCode()).isEqualTo(201);
        long userId = testData.userId(email);

        assertThat(api.get("/api/board").body()).isEqualTo("[]");
        assertThat(api.get("/api/stages/upcoming").body()).isEqualTo("[]");
        assertThat((Object) JsonPath.read(api.get("/api/cv").body(), "$.cv")).isNull();
        assertThat(api.getBytes("/api/cv/file").statusCode()).isEqualTo(404);
        assertThat((Boolean) JsonPath.read(api.get("/api/settings/integrations/mcp-token").body(), "$.configured")).isFalse();
        assertThat(JsonPath.<List<Object>>read(api.get("/api/job-data/export").body(), "$.jobs")).isEmpty();

        String settings = api.get("/api/settings").body();
        assertThat(JsonPath.<String>read(settings, "$.theme")).isEqualTo("SYSTEM");
        assertThat(JsonPath.<String>read(settings, "$.timezone")).isEqualTo("UTC");
        assertThat(JsonPath.<List<String>>read(settings, "$.visibleBoardStatuses"))
                .containsExactlyElementsOf(Settings.DEFAULT_VISIBLE_BOARD_STATUS_CODES);
        assertThat((Integer) JsonPath.read(settings, "$.minimumFitScore")).isEqualTo(Settings.DEFAULT_MINIMUM_FIT_SCORE);
        assertThat((Object) JsonPath.read(settings, "$.aiProvider")).isNull();
        assertThat((Object) JsonPath.read(settings, "$.aiModel")).isNull();
        assertThat((Boolean) JsonPath.read(settings, "$.anthropicApiKeyConfigured")).isFalse();
        assertThat((Boolean) JsonPath.read(settings, "$.geminiApiKeyConfigured")).isFalse();

        // Exactly one settings row, its own; nothing in any other user-owned table.
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = ? AND anthropic_api_key_encrypted IS NULL "
                + "AND gemini_api_key_encrypted IS NULL AND ai_provider IS NULL", userId)).isEqualTo(1);
        for (String table : List.of("jobs", "companies", "applications", "job_matches", "cvs", "integration_tokens")) {
            assertThat(testData.count("SELECT count(*) FROM " + table + " WHERE user_id = ?", userId)).as(table).isZero();
        }
    }

    @Test
    void emailIsStoredTrimmedWithCaseDotsAndPlusTagsUntouched() throws Exception {
        String email = "First.Last+Jobs-" + run + "@Example.COM";
        assertThat(new TestApiClient(port).register("  \t" + email + " \n", PASSWORD, PASSWORD).statusCode()).isEqualTo(201);
        assertThat(testData.strings("SELECT email FROM users WHERE upper(email) = upper(?)", email)).containsExactly(email);

        // Dots and plus tags are part of the address: these are different accounts.
        assertThat(new TestApiClient(port).register("firstlast+jobs-" + run + "@example.com", PASSWORD, PASSWORD).statusCode())
                .isEqualTo(201);
        assertThat(new TestApiClient(port).register("first.last-" + run + "@example.com", PASSWORD, PASSWORD).statusCode())
                .isEqualTo(201);
    }

    @Test
    void thePasswordIsUsedExactlyAsEnteredNeverTrimmed() throws Exception {
        String email = "spaces-" + run + "@example.com";
        String password = "  padded password  ";
        assertThat(new TestApiClient(port).register(email, password, password).statusCode()).isEqualTo(201);

        assertThat(new TestApiClient(port).login(email, password).statusCode()).isEqualTo(200);
        assertThat(new TestApiClient(port).login(email, password.strip()).statusCode()).isEqualTo(401);
    }

    // ===================== Duplicates =====================

    @Test
    void anExistingEmailIsRejectedWhateverItsCaseOrSurroundingWhitespace() throws Exception {
        String email = "dup-" + run + "@example.com";
        assertThat(new TestApiClient(port).register(email, PASSWORD, PASSWORD).statusCode()).isEqualTo(201);
        String hashBefore = testData.strings("SELECT password_hash FROM users WHERE email = ?", email).getFirst();

        for (String variant : List.of(email, email.toUpperCase(), "Dup-" + run + "@Example.Com", "  " + email + "  ")) {
            TestApiClient other = new TestApiClient(port);
            HttpResponse<String> response = other.register(variant, "another-password-1", "another-password-1");
            assertThat(response.statusCode()).as(variant).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo("An account with this email already exists");
            assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].field")).isEqualTo("email");
            assertThat(other.get("/api/auth/me").statusCode()).as("no session for a failed sign-up").isEqualTo(401);
        }
        assertThat(testData.count("SELECT count(*) FROM users WHERE upper(email) = upper(?)", email)).isEqualTo(1);
        assertThat(testData.strings("SELECT password_hash FROM users WHERE email = ?", email)).containsExactly(hashBefore);
        assertThat(new TestApiClient(port).login(email, PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void theInitialUserCannotBeRegisteredAgain() throws Exception {
        assertThat(new TestApiClient(port).register("TEST@jobhunter.local", PASSWORD, PASSWORD).statusCode()).isEqualTo(409);
        assertThat(new TestApiClient(port).login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentSignUpsForTheSameEmailCreateExactlyOneAccount() throws Exception {
        String email = "race-" + run + "@example.com";
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                // Differently cased variants race too: the case-insensitive index decides.
                String variant = i % 2 == 0 ? email : email.toUpperCase();
                TestApiClient client = new TestApiClient(port);
                client.get("/api/auth/csrf");
                Callable<Integer> attempt = () -> {
                    start.await();
                    return client.postJson("/api/auth/register", "{\"email\":" + jsonString(variant) + ",\"password\":"
                            + jsonString(PASSWORD) + ",\"confirmPassword\":" + jsonString(PASSWORD) + "}").statusCode();
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(testData.count("SELECT count(*) FROM users WHERE upper(email) = upper(?)", email)).isEqualTo(1);
        long userId = testData.userId(email);
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = ?", userId)).isEqualTo(1);
    }

    // ===================== Validation =====================

    @Test
    void invalidEmailsAreRejectedWithAFieldError() throws Exception {
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";
        assertThat(tooLong.length()).isGreaterThan(EmailAddresses.MAX_LENGTH);
        for (String email : List.of("", "   ", "plainaddress", "no-at.example.com", "a@b", "two@@example.com",
                "with space@example.com", "a@exa mple.com", "a@-example.com", "a@example..com", "<a>@example.com",
                "a".repeat(65) + "@example.com", tooLong)) {
            HttpResponse<String> response = new TestApiClient(port).register(email, PASSWORD, PASSWORD);
            assertThat(response.statusCode()).as(email).isEqualTo(400);
            assertThat(fieldErrors(response)).as(email).containsOnlyKeys("email");
        }
    }

    @Test
    void missingFieldsAreAllReportedAtOnce() throws Exception {
        TestApiClient api = new TestApiClient(port);
        api.get("/api/auth/csrf");

        HttpResponse<String> response = api.postJson("/api/auth/register", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldErrors(response)).containsOnlyKeys("email", "password", "confirmPassword");
        assertThat(api.postJson("/api/auth/register", "").statusCode()).isEqualTo(400);
        assertThat(api.postJson("/api/auth/register", "not json").statusCode()).isEqualTo(400);
    }

    @Test
    void passwordPolicyIsEnforced() throws Exception {
        Map<String, Boolean> passwords = Map.of(
                "seven77", false,
                "eight888", true,
                "x".repeat(72), true,
                "x".repeat(73), false,
                "é".repeat(36), true, // 72 bytes
                "é".repeat(37), false, // 74 bytes
                "        ", false,
                "😀".repeat(8), true); // 8 characters, 32 bytes
        for (Map.Entry<String, Boolean> entry : passwords.entrySet()) {
            String password = entry.getKey();
            String email = "policy-" + run + "-" + password.length() + "-" + password.hashCode() + "@example.com";
            HttpResponse<String> response = new TestApiClient(port).register(email, password, password);
            if (entry.getValue()) {
                assertThat(response.statusCode()).as(password).isEqualTo(201);
                assertThat(new TestApiClient(port).login(email, password).statusCode()).as(password).isEqualTo(200);
            } else {
                assertThat(response.statusCode()).as(password).isEqualTo(400);
                assertThat(fieldErrors(response)).as(password).containsOnlyKeys("password");
                assertThat(testData.count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
            }
        }
    }

    @Test
    void confirmationMustMatchExactly() throws Exception {
        String email = "confirm-" + run + "@example.com";
        for (String confirmation : List.of(PASSWORD + " ", PASSWORD.toUpperCase(), "")) {
            HttpResponse<String> response = new TestApiClient(port).register(email, PASSWORD, confirmation);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(fieldErrors(response)).containsOnlyKeys("confirmPassword");
        }
        assertThat(testData.count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    @Test
    void errorResponsesNeverEchoThePassword() throws Exception {
        String secret = "never-echo-this-" + run;
        HttpResponse<String> response = new TestApiClient(port).register("bad-email", secret, secret + "x");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain(secret);
    }

    // ===================== Security =====================

    @Test
    void signUpRequiresTheCsrfToken() throws Exception {
        String email = "csrf-" + run + "@example.com";
        TestApiClient api = new TestApiClient(port);
        api.get("/api/auth/csrf");

        HttpResponse<String> response = api.postJsonWithoutCsrfHeader("/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + "password-123" + "\",\"confirmPassword\":\"password-123\"}");

        // Refused by the CsrfFilter. For an anonymous request Spring Boot's (secured) error
        // dispatch turns the 403 into a 401, exactly as for a login without the token.
        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(testData.count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
        assertThat(api.get("/api/auth/me").statusCode()).isEqualTo(401);

        // Control: the same request carrying the token succeeds.
        HttpResponse<String> withToken = api.send("POST", "/api/auth/register", "application/json",
                "{\"email\":\"" + email + "\",\"password\":\"password-123\",\"confirmPassword\":\"password-123\"}");
        assertThat(withToken.statusCode()).isEqualTo(201);
    }

    @Test
    void onlyTheThreeFieldsAreRead() throws Exception {
        String email = "mass-" + run + "@example.com";
        TestApiClient api = new TestApiClient(port);
        api.get("/api/auth/csrf");

        HttpResponse<String> response = api.postJson("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":"
                + jsonString(PASSWORD) + ",\"confirmPassword\":" + jsonString(PASSWORD) + ",\"id\":1,\"userId\":1,"
                + "\"passwordHash\":\"$2a$10$abcdefghijklmnopqrstuuF2mI2vV0FbYgY6hD1Yq4k7Yq0aY6c2a\",\"fullName\":\"Admin\","
                + "\"roles\":[\"ROLE_ADMIN\"],\"createdAt\":\"2000-01-01T00:00:00Z\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        long id = JsonPath.<Number>read(response.body(), "$.id").longValue();
        assertThat(id).isNotEqualTo(1L);
        assertThat((Object) JsonPath.read(response.body(), "$.fullName")).isNull();
        assertThat(testData.strings("SELECT password_hash FROM users WHERE id = ?", id).getFirst())
                .doesNotContain("abcdefghijklmnopqrstuu");
        assertThat(testData.count("SELECT count(*) FROM users WHERE id = ? AND created_at > now() - interval '1 hour'", id))
                .isEqualTo(1);
        assertThat(new TestApiClient(port).login(email, PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void aLoggedInSessionCannotSignUpAnotherAccount() throws Exception {
        TestApiClient api = new TestApiClient(port);
        assertThat(api.register("first-" + run + "@example.com", PASSWORD, PASSWORD).statusCode()).isEqualTo(201);

        HttpResponse<String> second = api.register("second-" + run + "@example.com", PASSWORD, PASSWORD);

        assertThat(second.statusCode()).isEqualTo(400);
        assertThat(testData.count("SELECT count(*) FROM users WHERE email = ?", "second-" + run + "@example.com")).isZero();
        assertThat(JsonPath.<String>read(api.get("/api/auth/me").body(), "$.email")).isEqualTo("first-" + run + "@example.com");
    }

    @Test
    void onlyPostIsPublicOnTheSignUpPath() throws Exception {
        TestApiClient api = new TestApiClient(port);
        api.get("/api/auth/csrf");
        for (String method : List.of("GET", "PUT", "PATCH", "DELETE")) {
            assertThat(api.send(method, "/api/auth/register", "application/json", "{}").statusCode()).as(method).isEqualTo(401);
        }
        assertThat(api.postJson("/api/auth/register/", "{}").statusCode()).isIn(401, 404);
        assertThat(api.get("/api/board").statusCode()).isEqualTo(401);
    }

    @Test
    void loginWithAPasswordLongerThanBcryptAcceptsIsAPlainFailure() throws Exception {
        // BCrypt itself throws for > 72 bytes; that must stay a normal "invalid credentials".
        assertThat(new TestApiClient(port).login("test@jobhunter.local", "p".repeat(200)).statusCode()).isEqualTo(401);
        assertThat(new TestApiClient(port).login("nobody-" + run + "@example.com", "p".repeat(200)).statusCode()).isEqualTo(401);
    }

    private static Map<String, String> fieldErrors(HttpResponse<String> response) {
        List<Map<String, String>> errors = JsonPath.read(response.body(), "$.fieldErrors");
        return errors.stream().collect(java.util.stream.Collectors.toMap(e -> e.get("field"), e -> e.get("message")));
    }
}
