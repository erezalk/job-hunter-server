package dev.erezalkalay.jobhunter.cv;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class CvIntegrationTest extends IntegrationTestBase {

    private static final String CV_TEXT =
            "Erez Alkalay - Senior Java Engineer. 10 years building Spring Boot and PostgreSQL backends.";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataSupport testDataSupport;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        HttpResponse<String> login = client.login("test@jobhunter.local", "test-password-123");
        assertThat(login.statusCode()).isEqualTo(200);

        // Other integration test classes share this same Testcontainers
        // Postgres/user (see IntegrationTestBase) — clear any CV they left
        // active so each test here starts from a known "no CV" state.
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
    }

    @Test
    void noCvUploadedInitially() throws Exception {
        HttpResponse<String> response = client.get("/api/cv");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(response.body(), "$.cv")).isNull();
    }

    @Test
    void uploadingPdfExtractsText() throws Exception {
        byte[] pdf = TestFiles.pdfWithText(CV_TEXT);
        HttpResponse<String> upload = client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf", pdf);
        assertThat(upload.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(upload.body(), "$.extractionStatus")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<String>read(upload.body(), "$.extractedText")).contains("Senior Java Engineer");
        assertThat(JsonPath.<String>read(upload.body(), "$.fileName")).isEqualTo("cv.pdf");
    }

    @Test
    void rejectsDocxUpload() throws Exception {
        // CV support is PDF-only — another format such as DOCX must be
        // rejected, not silently downgraded or ignored.
        HttpResponse<String> upload = client.postMultipartFile(
                "/api/cv", "file", "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not a real docx, only the content-type/extension matter here".getBytes());
        assertThat(upload.statusCode()).isEqualTo(400);
    }

    @Test
    void scannedPdfWithNoTextReportsEmptyStatusWithWarning() throws Exception {
        byte[] pdf = TestFiles.emptyPdf();
        HttpResponse<String> upload = client.postMultipartFile("/api/cv", "file", "scanned.pdf", "application/pdf", pdf);
        assertThat(upload.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(upload.body(), "$.extractionStatus")).isEqualTo("EMPTY");
        assertThat(JsonPath.<String>read(upload.body(), "$.extractionWarning")).isNotBlank();
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        HttpResponse<String> upload = client.postMultipartFile(
                "/api/cv", "file", "cv.txt", "text/plain", "just plain text".getBytes());
        assertThat(upload.statusCode()).isEqualTo(400);
    }

    @Test
    void replacingCvMakesTheNewOneActive() throws Exception {
        byte[] first = TestFiles.pdfWithText(CV_TEXT);
        client.postMultipartFile("/api/cv", "file", "old-cv.pdf", "application/pdf", first);

        byte[] second = TestFiles.pdfWithText(CV_TEXT + " Updated with new project experience.");
        HttpResponse<String> secondUpload = client.postMultipartFile(
                "/api/cv", "file", "new-cv.pdf", "application/pdf", second);
        assertThat(secondUpload.statusCode()).isEqualTo(201);

        HttpResponse<String> active = client.get("/api/cv");
        assertThat(JsonPath.<String>read(active.body(), "$.cv.fileName")).isEqualTo("new-cv.pdf");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        TestApiClient anonymousClient = new TestApiClient(port);
        HttpResponse<String> response = anonymousClient.get("/api/cv");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void activeCvFileReturnsPdfInline() throws Exception {
        byte[] pdf = TestFiles.pdfWithText(CV_TEXT);
        client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf", pdf);

        HttpResponse<byte[]> file = client.getBytes("/api/cv/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("inline"));
        assertThat(file.body()).isNotEmpty();
        assertThat(new String(file.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void activeCvFileReturns404WhenNoActiveCv() throws Exception {
        HttpResponse<byte[]> response = client.getBytes("/api/cv/file");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void activeCvFileRequiresAuthentication() throws Exception {
        TestApiClient anonymousClient = new TestApiClient(port);
        HttpResponse<byte[]> response = anonymousClient.getBytes("/api/cv/file");
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
