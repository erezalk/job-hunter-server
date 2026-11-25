package dev.erezalkalay.jobhunter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.cv.Cv;
import dev.erezalkalay.jobhunter.cv.CvRepository;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Bearer-token access to the active CV for MCP clients (get_cv):
 * GET /api/integrations/cv (metadata + extracted text) and
 * GET /api/integrations/cv/file (the original PDF bytes). The CV itself is
 * uploaded through the normal session API, i.e. the one CV storage the CV page
 * uses. Every test uses freshly created users on the shared database.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntegrationCvIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "cv-integration-password-123";
    private static final String TEXT_URL = "/api/integrations/cv";
    private static final String FILE_URL = "/api/integrations/cv/file";

    /** Representative two-page CV; each line must come back, in order. */
    private static final List<List<String>> CV_PAGES = List.of(
            List.of(
                    "Jane Example - Senior Backend Engineer",
                    "Berlin, Germany | jane@example.com",
                    "Summary",
                    "12 years building Java and Spring Boot services on PostgreSQL and Kafka.",
                    "Experience",
                    "Acme GmbH - Staff Engineer (2021 - present)",
                    "• Led the payments platform migration to event sourcing.",
                    "• Cut p99 latency by 40% across 30 services."),
            List.of(
                    "Beta AG - Senior Engineer (2016 - 2021)",
                    "• Designed the multi-tenant billing API (Café – Résumé edition).",
                    "Education",
                    "M.Sc. Computer Science, Example University",
                    "Skills",
                    "Java 25, Spring Boot, PostgreSQL, Kafka, Kubernetes, AWS"));

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private CvRepository cvRepository;

    // No cookie handler: exactly what the MCP server is, a cookie-less bearer client.
    private final HttpClient bare = HttpClient.newHttpClient();
    private String run;

    @BeforeEach
    void setUp() {
        run = String.valueOf(System.nanoTime());
    }

    // --- Original PDF ----------------------------------------------------------

    @Test
    void returnsTheOriginalPdfByteForByteWithMimeTypeAndFileName() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] pdf = TestFiles.pdfWithPages(CV_PAGES);
        upload(alice, "Jane_Example_CV.pdf", pdf);
        String token = generateToken(alice);

        HttpResponse<byte[]> file = bearerBytes(FILE_URL, token);

        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(file.body()).isEqualTo(pdf);
        assertThat(file.headers().firstValue("Content-Length")).hasValue(String.valueOf(pdf.length));
        assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(value -> assertThat(value)
                .startsWith("attachment")
                .contains("filename=\"Jane_Example_CV.pdf\"")
                .contains("filename*=UTF-8''Jane_Example_CV.pdf"));
        // CV content must never be cached by intermediaries.
        assertThat(file.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    @Test
    void preservesANonAsciiFileNameInTheRfc5987Form() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "Résumé Jörg – 2026.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);

        HttpResponse<byte[]> file = bearerBytes(FILE_URL, token);

        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(value -> assertThat(value)
                .contains("filename*=UTF-8''R%C3%A9sum%C3%A9%20J%C3%B6rg%20%E2%80%93%202026.pdf"));
        assertThat(JsonPath.<String>read(bearer(TEXT_URL, token).body(), "$.fileName"))
                .isEqualTo("Résumé Jörg – 2026.pdf");
    }

    // --- Extracted text --------------------------------------------------------

    @Test
    void returnsTheCompleteExtractedTextAndMetadata() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] pdf = TestFiles.pdfWithPages(CV_PAGES);
        upload(alice, "Jane_Example_CV.pdf", pdf);
        String token = generateToken(alice);

        HttpResponse<String> response = bearer(TEXT_URL, token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        String body = response.body();
        assertThat(JsonPath.<String>read(body, "$.fileName")).isEqualTo("Jane_Example_CV.pdf");
        assertThat(JsonPath.<String>read(body, "$.contentType")).isEqualTo("application/pdf");
        assertThat(JsonPath.<Number>read(body, "$.fileSizeBytes").longValue()).isEqualTo(pdf.length);
        assertThat(JsonPath.<String>read(body, "$.uploadedAt")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.extractionStatus")).isEqualTo("SUCCESS");
        assertThat((Object) JsonPath.read(body, "$.extractionWarning")).isNull();

        String text = JsonPath.read(body, "$.extractedText");
        List<String> extractedLines = Arrays.stream(text.split("\\R")).map(String::strip).filter(l -> !l.isEmpty()).toList();
        List<String> expectedLines = CV_PAGES.stream().flatMap(List::stream).toList();
        // Every line, verbatim and in order: nothing summarised, rewritten or dropped.
        assertThat(extractedLines).containsExactlyElementsOf(expectedLines);
    }

    @Test
    void returnsTheSameTextTheCvPageShows() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "cv.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);

        String sessionText = JsonPath.read(alice.get("/api/cv").body(), "$.cv.extractedText");
        String integrationText = JsonPath.read(bearer(TEXT_URL, token).body(), "$.extractedText");

        assertThat(integrationText).isEqualTo(sessionText);
    }

    @Test
    void extractsOnTheFlyForAStoredCvWithoutText() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "cv.pdf", TestFiles.pdfWithPages(CV_PAGES));
        Cv cv = cvRepository.findByUserIdAndPrimaryTrue(testData.userId(email("alice"))).orElseThrow();
        cv.setExtractedText(null);
        cv.setExtractionStatus("PENDING");
        cvRepository.save(cv);
        String token = generateToken(alice);

        String body = bearer(TEXT_URL, token).body();

        assertThat(JsonPath.<String>read(body, "$.extractionStatus")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<String>read(body, "$.extractedText")).contains("Jane Example - Senior Backend Engineer", "AWS");
        // Read-only: nothing is written back.
        assertThat(cvRepository.findById(cv.getId()).orElseThrow().getExtractedText()).isNull();
    }

    // --- Missing, empty and invalid CVs ---------------------------------------

    @Test
    void answers404WithCvNotFoundWhenNoCvWasUploaded() throws Exception {
        String token = generateToken(newUser("alice"));

        HttpResponse<String> text = bearer(TEXT_URL, token);
        assertThat(text.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(text.body(), "$.code")).isEqualTo("CV_NOT_FOUND");

        HttpResponse<String> file = bearer(FILE_URL, token);
        assertThat(file.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(file.body(), "$.code")).isEqualTo("CV_NOT_FOUND");

        // Exactly what the MCP server sends for the file: a PDF preferred, JSON acceptable for errors.
        HttpRequest mcpStyle = HttpRequest.newBuilder(URI.create("http://localhost:" + port + FILE_URL))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/pdf, application/json;q=0.9")
                .GET().build();
        HttpResponse<String> negotiated = bare.send(mcpStyle, BodyHandlers.ofString());
        assertThat(negotiated.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(negotiated.body(), "$.code")).isEqualTo("CV_NOT_FOUND");
    }

    @Test
    void servesThePdfToAClientThatPrefersPdfButAcceptsJson() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] pdf = TestFiles.pdfWithPages(CV_PAGES);
        upload(alice, "cv.pdf", pdf);
        String token = generateToken(alice);

        HttpRequest mcpStyle = HttpRequest.newBuilder(URI.create("http://localhost:" + port + FILE_URL))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/pdf, application/json;q=0.9")
                .GET().build();
        HttpResponse<byte[]> file = bare.send(mcpStyle, BodyHandlers.ofByteArray());

        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(file.body()).isEqualTo(pdf);
    }

    @Test
    void anUnknownIntegrationRouteIsA404WithoutTheCvCode() throws Exception {
        String token = generateToken(newUser("alice"));

        HttpResponse<String> response = bearer("/api/integrations/cv/unknown", token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("CV_NOT_FOUND");
    }

    @Test
    void reportsACorruptedPdfAsFailedExtractionAndStillServesTheOriginalBytes() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] valid = TestFiles.pdfWithPages(CV_PAGES);
        byte[] truncated = Arrays.copyOf(valid, 40);
        upload(alice, "broken.pdf", truncated);
        String token = generateToken(alice);

        String body = bearer(TEXT_URL, token).body();
        assertThat(JsonPath.<String>read(body, "$.extractionStatus")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(body, "$.extractionWarning")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.extractedText")).isEmpty();

        HttpResponse<byte[]> file = bearerBytes(FILE_URL, token);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(truncated);
    }

    @Test
    void reportsANonPdfWithAPdfNameAsFailedExtraction() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] garbage = "this is not a PDF at all".getBytes(StandardCharsets.UTF_8);
        upload(alice, "fake.pdf", garbage);
        String token = generateToken(alice);

        assertThat(JsonPath.<String>read(bearer(TEXT_URL, token).body(), "$.extractionStatus")).isEqualTo("FAILED");
        assertThat(bearerBytes(FILE_URL, token).body()).isEqualTo(garbage);
    }

    @Test
    void reportsAScannedPdfWithoutTextAsEmpty() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "scanned.pdf", TestFiles.emptyPdf());
        String token = generateToken(alice);

        String body = bearer(TEXT_URL, token).body();
        assertThat(JsonPath.<String>read(body, "$.extractionStatus")).isEqualTo("EMPTY");
        assertThat(JsonPath.<String>read(body, "$.extractionWarning")).contains("scanned");
    }

    // --- Authentication ---------------------------------------------------------

    @Test
    void rejectsMissingInvalidAndRevokedTokensAndSessionCookies() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "cv.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);
        long tokenId = JsonPath.<Number>read(alice.get("/api/settings/integrations/tokens").body(), "$[0].id").longValue();

        for (String url : List.of(TEXT_URL, FILE_URL)) {
            assertThat(bearer(url, null).statusCode()).as(url).isEqualTo(401);
            assertThat(bearer(url, "jh_not-a-real-token").statusCode()).as(url).isEqualTo(401);
            assertThat(bearer(url, "not-even-prefixed").statusCode()).as(url).isEqualTo(401);
            // A logged-in browser session is not an integration credential.
            assertThat(alice.get(url).statusCode()).as(url).isEqualTo(401);
            HttpResponse<String> unauthorized = bearer(url, "jh_not-a-real-token");
            assertThat(unauthorized.body()).doesNotContain("Jane Example");
        }

        assertThat(alice.deleteRequest("/api/settings/integrations/tokens/" + tokenId).statusCode()).isEqualTo(204);
        assertThat(bearer(TEXT_URL, token).statusCode()).isEqualTo(401);
        assertThat(bearer(FILE_URL, token).statusCode()).isEqualTo(401);
    }

    @Test
    void aBearerTokenCannotReachTheSessionCvEndpoints() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "cv.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);

        assertThat(bearer("/api/cv", token).statusCode()).isEqualTo(401);
        assertThat(bearer("/api/cv/file", token).statusCode()).isEqualTo(401);
    }

    @Test
    void offersNoWriteAccessToTheCv() throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "cv.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + TEXT_URL))
                    .header("Authorization", "Bearer " + token)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            assertThat(bare.send(request, BodyHandlers.ofString()).statusCode()).as(method).isEqualTo(405);
        }
    }

    // --- Isolation and replacement ----------------------------------------------

    @Test
    void eachTokenOnlyEverSeesItsOwnUsersCv() throws Exception {
        TestApiClient alice = newUser("alice");
        TestApiClient bob = newUser("bob");
        TestApiClient carol = newUser("carol");
        byte[] alicePdf = TestFiles.pdfWithText("Alice Only - Kotlin Engineer with a long enough CV text line.");
        byte[] bobPdf = TestFiles.pdfWithText("Bob Only - Go Engineer with a long enough CV text line here.");
        upload(alice, "alice.pdf", alicePdf);
        upload(bob, "bob.pdf", bobPdf);
        String aliceToken = generateToken(alice);
        String bobToken = generateToken(bob);
        String carolToken = generateToken(carol);

        assertThat(JsonPath.<String>read(bearer(TEXT_URL, aliceToken).body(), "$.extractedText")).contains("Alice Only").doesNotContain("Bob");
        assertThat(JsonPath.<String>read(bearer(TEXT_URL, bobToken).body(), "$.extractedText")).contains("Bob Only").doesNotContain("Alice");
        assertThat(bearerBytes(FILE_URL, aliceToken).body()).isEqualTo(alicePdf);
        assertThat(bearerBytes(FILE_URL, bobToken).body()).isEqualTo(bobPdf);

        // A user without a CV never falls back to somebody else's.
        assertThat(bearer(TEXT_URL, carolToken).statusCode()).isEqualTo(404);
        assertThat(bearer(FILE_URL, carolToken).statusCode()).isEqualTo(404);

        // There is no parameter that could select another user or CV.
        long bobId = testData.userId(email("bob"));
        long bobCvId = cvRepository.findByUserIdAndPrimaryTrue(bobId).orElseThrow().getId();
        for (String query : List.of("?userId=" + bobId, "?cvId=" + bobCvId, "?id=" + bobCvId, "?email=" + email("bob"))) {
            assertThat(JsonPath.<String>read(bearer(TEXT_URL + query, aliceToken).body(), "$.extractedText"))
                    .as(query).contains("Alice Only");
            assertThat(bearerBytes(FILE_URL + query, aliceToken).body()).as(query).isEqualTo(alicePdf);
            assertThat(bearer(TEXT_URL + query, carolToken).statusCode()).as(query).isEqualTo(404);
        }
        assertThat(bearer(TEXT_URL + "/" + bobCvId, aliceToken).statusCode()).isEqualTo(404);
    }

    @Test
    void aReplacedCvIsServedImmediately() throws Exception {
        TestApiClient alice = newUser("alice");
        byte[] first = TestFiles.pdfWithText("First version of the CV with enough text to be meaningful.");
        upload(alice, "cv-v1.pdf", first);
        String token = generateToken(alice);
        assertThat(JsonPath.<String>read(bearer(TEXT_URL, token).body(), "$.extractedText")).contains("First version");
        assertThat(bearerBytes(FILE_URL, token).body()).isEqualTo(first);

        byte[] second = TestFiles.pdfWithText("Second version of the CV, replacing the first one entirely.");
        upload(alice, "cv-v2.pdf", second);

        String body = bearer(TEXT_URL, token).body();
        assertThat(JsonPath.<String>read(body, "$.fileName")).isEqualTo("cv-v2.pdf");
        assertThat(JsonPath.<String>read(body, "$.extractedText")).contains("Second version").doesNotContain("First version");
        HttpResponse<byte[]> file = bearerBytes(FILE_URL, token);
        assertThat(file.body()).isEqualTo(second);
        assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("cv-v2.pdf"));
    }

    // --- Logging -----------------------------------------------------------------

    @Test
    void neverLogsCvContentOrTheToken(CapturedOutput output) throws Exception {
        TestApiClient alice = newUser("alice");
        upload(alice, "Jane_Example_CV.pdf", TestFiles.pdfWithPages(CV_PAGES));
        String token = generateToken(alice);
        upload(newUser("bob"), "broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));

        bearer(TEXT_URL, token);
        bearerBytes(FILE_URL, token);
        bearer(TEXT_URL, "jh_not-a-real-token");

        assertThat(output.getAll())
                .doesNotContain("Senior Backend Engineer")
                .doesNotContain("event sourcing")
                .doesNotContain(token);
    }

    // --- helpers -------------------------------------------------------------------

    private String email(String name) {
        return name + "-cvint-" + run + "@jobhunter.local";
    }

    private TestApiClient newUser(String name) throws Exception {
        testData.createUserIfMissing(email(name), PASSWORD);
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(email(name), PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private static void upload(TestApiClient client, String fileName, byte[] content) throws Exception {
        HttpResponse<String> response = client.postMultipartFile("/api/cv", "file", fileName, "application/pdf", content);
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private static String generateToken(TestApiClient client) throws Exception {
        HttpResponse<String> response = client.postJson("/api/settings/integrations/tokens", "{}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private HttpRequest get(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.build();
    }

    private HttpResponse<String> bearer(String path, String token) throws Exception {
        return bare.send(get(path, token), BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> bearerBytes(String path, String token) throws Exception {
        return bare.send(get(path, token), BodyHandlers.ofByteArray());
    }
}
