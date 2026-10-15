package dev.erezalkalay.jobhunter.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The auth rate limits over real HTTP, with small limits and refill periods
 * far longer than a test run (no sleeping; nothing refills mid-test). Every
 * request comes from 127.0.0.1, so the per-IP buckets are shared by the whole
 * test; the store is cleared before each.
 */
@TestPropertySource(properties = {
        "app.auth.rate-limit.login-per-ip.capacity=8",
        "app.auth.rate-limit.login-per-ip.refill-period=1h",
        "app.auth.rate-limit.login-per-email.capacity=3",
        "app.auth.rate-limit.login-per-email.refill-period=1h",
        "app.auth.rate-limit.signup-per-ip.capacity=2",
        "app.auth.rate-limit.signup-per-ip.refill-period=1h",
        "app.auth.rate-limit.password-change-per-user.capacity=2",
        "app.auth.rate-limit.password-change-per-user.refill-period=1h",
})
class AuthRateLimitIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "rate-limit-password-1";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private InMemoryRateLimitStore store;

    private String run;

    @BeforeEach
    void setUp() {
        store.clear();
        run = System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    // ===================== Login =====================

    @Test
    void normalLoginsAreNotLimited() throws Exception {
        String email = user("normal");
        for (int i = 0; i < 3; i++) {
            assertThat(new TestApiClient(port).login(email, PASSWORD).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void repeatedAttemptsOnOneEmailAreLimitedWhateverItsCaseOrWhitespace() throws Exception {
        String email = user("target");
        for (int i = 0; i < 3; i++) {
            assertThat(new TestApiClient(port).login(email, "wrong-" + i).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> limited = new TestApiClient(port).login(email, PASSWORD);
        assertThat(limited.statusCode()).as("even the right password waits").isEqualTo(429);
        assertThat(JsonPath.<String>read(limited.body(), "$.code")).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 1200);
        assertThat(limited.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        assertThat(new TestApiClient(port).login("  " + email.toUpperCase() + " ", PASSWORD).statusCode()).isEqualTo(429);

        // Other accounts are unaffected.
        assertThat(new TestApiClient(port).login(user("other"), PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void spellingsTheDatabaseTreatsAsTheSameEmailShareTheLimit() throws Exception {
        String email = user("login");
        // PostgreSQL's upper() maps the dotless "ı" to "I": this spelling logs into the same account...
        String variant = email.replace("login", "logın");
        assertThat(new TestApiClient(port).login(variant, PASSWORD).statusCode()).isEqualTo(200);
        for (int i = 0; i < 2; i++) {
            assertThat(new TestApiClient(port).login(email, "wrong-" + i).statusCode()).isEqualTo(401);
        }
        // ...so it must not get a fresh bucket of guesses.
        assertThat(new TestApiClient(port).login(variant, PASSWORD).statusCode()).isEqualTo(429);
    }

    @Test
    void theLimiterAnswersIdenticallyForExistingAndUnknownEmails() throws Exception {
        String existing = user("exists");
        String unknown = "unknown-" + run + "@example.com";

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> a = new TestApiClient(port).login(existing, "wrong-password");
            HttpResponse<String> b = new TestApiClient(port).login(unknown, "wrong-password");
            assertThat(a.statusCode()).isEqualTo(b.statusCode()).isEqualTo(401);
            assertThat(withoutTimestamp(a.body())).isEqualTo(withoutTimestamp(b.body()));
        }
        HttpResponse<String> a = new TestApiClient(port).login(existing, "wrong-password");
        HttpResponse<String> b = new TestApiClient(port).login(unknown, "wrong-password");
        assertThat(a.statusCode()).isEqualTo(b.statusCode()).isEqualTo(429);
        assertThat(withoutRetryAfter(withoutTimestamp(a.body()))).isEqualTo(withoutRetryAfter(withoutTimestamp(b.body())));
    }

    @Test
    void manyAccountsFromOneAddressAreLimited() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(new TestApiClient(port).login("spray-" + i + "-" + run + "@example.com", "guess").statusCode())
                    .isEqualTo(401);
        }
        assertThat(new TestApiClient(port).login(user("victim"), PASSWORD).statusCode()).isEqualTo(429);
    }

    // ===================== Sign-up =====================

    @Test
    void signUpsFromOneAddressAreLimitedAndInvalidRequestsDoNotCount() throws Exception {
        assertThat(new TestApiClient(port).register("not-an-email", PASSWORD, PASSWORD).statusCode()).isEqualTo(400);
        assertThat(new TestApiClient(port).register("short-" + run + "@example.com", "short", "short").statusCode()).isEqualTo(400);
        assertThat(new TestApiClient(port).register("s1-" + run + "@example.com", PASSWORD, PASSWORD).statusCode()).isEqualTo(201);
        assertThat(new TestApiClient(port).register("s2-" + run + "@example.com", PASSWORD, PASSWORD).statusCode()).isEqualTo(201);

        HttpResponse<String> limited = new TestApiClient(port).register("s3-" + run + "@example.com", PASSWORD, PASSWORD);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(testData.count("SELECT count(*) FROM users WHERE email = ?", "s3-" + run + "@example.com")).isZero();

        // Past the limit, an existing email gets the same 429, not "already exists".
        HttpResponse<String> existing = new TestApiClient(port).register("s1-" + run + "@example.com", PASSWORD, PASSWORD);
        assertThat(existing.statusCode()).isEqualTo(429);
        assertThat(existing.body()).doesNotContain("already exists");
    }

    // ===================== Change password =====================

    @Test
    void currentPasswordGuessesAreLimitedPerUser() throws Exception {
        String email = user("changer");
        TestApiClient api = new TestApiClient(port);
        assertThat(api.login(email, PASSWORD).statusCode()).isEqualTo(200);
        String hashBefore = testData.strings("SELECT password_hash FROM users WHERE email = ?", email).getFirst();

        // Requests failing validation do not count; wrong guesses do.
        assertThat(api.changePassword(PASSWORD, "new-password-1", "mismatch-123").statusCode()).isEqualTo(400);
        assertThat(api.changePassword("guess-1", "new-password-1", "new-password-1").statusCode()).isEqualTo(400);
        assertThat(api.changePassword("guess-2", "new-password-1", "new-password-1").statusCode()).isEqualTo(400);

        HttpResponse<String> limited = api.changePassword(PASSWORD, "new-password-1", "new-password-1");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(testData.strings("SELECT password_hash FROM users WHERE email = ?", email)).containsExactly(hashBefore);
        assertThat(api.get("/api/auth/me").statusCode()).as("a 429 does not end the session").isEqualTo(200);

        // Another user's changes are counted separately.
        String otherEmail = user("other-changer");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(otherEmail, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(other.changePassword(PASSWORD, "new-password-1", "new-password-1").statusCode()).isEqualTo(204);
    }

    private String user(String prefix) {
        String email = prefix + "-" + run + "@example.com";
        testData.createUserIfMissing(email, PASSWORD);
        return email;
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    private static String withoutRetryAfter(String body) {
        return body.replaceAll("\"retryAfterSeconds\":\\d+", "");
    }
}
