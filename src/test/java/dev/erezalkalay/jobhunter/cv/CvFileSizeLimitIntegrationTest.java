package dev.erezalkalay.jobhunter.cv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cv.max-file-size-bytes=200"
)
class CvFileSizeLimitIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        HttpResponse<String> login = client.login("test@jobhunter.local", "test-password-123");
        assertThat(login.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsFileLargerThanConfiguredLimit() throws Exception {
        byte[] oversized = TestFiles.pdfWithText(
                "Padding text repeated to exceed the tiny 200-byte test limit. ".repeat(20));
        HttpResponse<String> upload = client.postMultipartFile(
                "/api/cv", "file", "big.pdf", "application/pdf", oversized);
        assertThat(upload.statusCode()).isEqualTo(400);
    }
}
