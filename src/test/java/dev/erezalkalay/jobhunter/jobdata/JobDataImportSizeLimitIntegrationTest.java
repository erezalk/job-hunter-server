package dev.erezalkalay.jobhunter.jobdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.job-data.import-max-bytes=2048")
class JobDataImportSizeLimitIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Test
    void anOversizedImportIsRejectedBeforeAnythingIsParsedOrWritten() throws Exception {
        String email = "jobdata-size-" + System.nanoTime() + "@jobhunter.local";
        testData.createUserIfMissing(email, "jobdata-password-123");
        TestApiClient client = new TestApiClient(port);
        assertThat(client.login(email, "jobdata-password-123").statusCode()).isEqualTo(200);
        String file = "{\"format\":\"job-hunter-jobs\",\"formatVersion\":1,\"exportedAt\":\"2025-06-01T00:00:00Z\",\"jobs\":[],"
                + "\"padding\":\"" + "x".repeat(4096) + "\"}";

        HttpResponse<String> response = client.postJson("/api/job-data/import", file);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("too large");
        assertThat(JsonPath.<List<Object>>read(client.get("/api/board").body(), "$")).isEmpty();
    }
}
