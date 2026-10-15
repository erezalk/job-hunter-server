package dev.erezalkalay.jobhunter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class ApplicationDeletionIntegrationTest extends IntegrationTestBase {

    private static final String OTHER_EMAIL = "other@jobhunter.local";
    private static final String OTHER_PASSWORD = "other-password-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
    }

    @Test
    void ownerDeletesJobWithAllDependentData() throws Exception {
        HttpResponse<String> added = addJob(client, "Delete Corp", "Delete Engineer");
        int applicationId = JsonPath.read(added.body(), "$.id");
        int jobId = JsonPath.read(added.body(), "$.jobId");

        client.postJson("/api/applications/" + applicationId + "/stages", "{\"name\":\"Phone screen\"}");
        client.patchJson("/api/applications/" + applicationId + "/notes", "{\"notes\":\"some notes\"}");
        client.patchJson("/api/applications/" + applicationId + "/status", "{\"statusCode\":\"CV_SENT\"}");
        testData.createJobMatch((long) jobId, 80);

        assertThat(testData.count("SELECT count(*) FROM application_stages WHERE application_id = ?", applicationId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM application_status_history WHERE application_id = ?", applicationId)).isEqualTo(2);

        assertThat(client.deleteRequest("/api/applications/" + applicationId).statusCode()).isEqualTo(204);

        assertThat(client.get("/api/applications/" + applicationId).statusCode()).isEqualTo(404);
        assertThat(JsonPath.<List<Integer>>read(client.get("/api/board").body(), "$[*].applicationId")).doesNotContain(applicationId);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE id = ?", applicationId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM application_stages WHERE application_id = ?", applicationId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM application_status_history WHERE application_id = ?", applicationId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", jobId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE id = ?", jobId)).isZero();
    }

    @Test
    void deletingAnUnknownApplicationIs404AndUnauthenticatedIs401() throws Exception {
        assertThat(client.deleteRequest("/api/applications/999999999").statusCode()).isEqualTo(404);
        assertThat(new TestApiClient(port).deleteRequest("/api/applications/1").statusCode()).isEqualTo(401);
    }

    @Test
    void anotherUserCannotDeleteAndDeletingMyJobNeverTouchesTheirs() throws Exception {
        testData.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);

        HttpResponse<String> mine = addJob(client, "Shared Corp", "Shared Engineer");
        int myApplicationId = JsonPath.read(mine.body(), "$.id");
        int myJobId = JsonPath.read(mine.body(), "$.jobId");

        // The other user cannot see or delete my application.
        assertThat(other.deleteRequest("/api/applications/" + myApplicationId).statusCode()).isEqualTo(404);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE id = ?", myApplicationId)).isEqualTo(1);

        // They add the same company + title: their own, independent job, application and company.
        HttpResponse<String> theirs = addJob(other, "shared corp", "shared engineer");
        assertThat(theirs.statusCode()).isEqualTo(201);
        int theirApplicationId = JsonPath.read(theirs.body(), "$.id");
        int theirJobId = JsonPath.read(theirs.body(), "$.jobId");
        assertThat(theirJobId).isNotEqualTo(myJobId);
        assertThat(theirApplicationId).isNotEqualTo(myApplicationId);
        testData.createJobMatch((long) theirJobId, 55);
        long theirCompanyId = companyOf(theirJobId);
        assertThat(theirCompanyId).isNotEqualTo(companyOf(myJobId));

        // Deleting mine removes my job and company, and leaves all of theirs alone.
        long myCompanyId = companyOf(myJobId);
        assertThat(client.deleteRequest("/api/applications/" + myApplicationId).statusCode()).isEqualTo(204);
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE id = ?", myJobId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE id = ?", myCompanyId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE id = ?", theirJobId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE id = ?", theirApplicationId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", theirJobId)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE id = ?", theirCompanyId)).isEqualTo(1);

        assertThat(other.deleteRequest("/api/applications/" + theirApplicationId).statusCode()).isEqualTo(204);
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE id = ?", theirJobId)).isZero();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE id = ?", theirCompanyId)).isZero();
    }

    @Test
    void aCompanyStaysWhileAnotherOfMyJobsUsesIt() throws Exception {
        String company = "Kept Corp " + System.nanoTime();
        HttpResponse<String> first = addJob(client, company, "First Kept Engineer");
        HttpResponse<String> second = addJob(client, company, "Second Kept Engineer");
        long companyId = companyOf(JsonPath.<Integer>read(first.body(), "$.jobId"));
        assertThat(companyOf(JsonPath.<Integer>read(second.body(), "$.jobId"))).isEqualTo(companyId);

        assertThat(client.deleteRequest("/api/applications/" + JsonPath.read(first.body(), "$.id")).statusCode()).isEqualTo(204);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE id = ?", companyId)).isEqualTo(1);

        assertThat(client.deleteRequest("/api/applications/" + JsonPath.read(second.body(), "$.id")).statusCode()).isEqualTo(204);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE id = ?", companyId)).isZero();
    }

    private long companyOf(int jobId) {
        return Long.parseLong(testData.strings("SELECT company_id::text FROM jobs WHERE id = ?", jobId).get(0));
    }

    @Test
    void deletedJobCanBeAddedAgain() throws Exception {
        HttpResponse<String> first = addJob(client, "Readd Corp", "Readd Engineer");
        int applicationId = JsonPath.read(first.body(), "$.id");
        assertThat(client.deleteRequest("/api/applications/" + applicationId).statusCode()).isEqualTo(204);

        HttpResponse<String> again = addJob(client, "Readd Corp", "Readd Engineer");
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(again.body(), "$.id")).isNotEqualTo(applicationId);
    }

    @Test
    void archivedJobStillCountsAsADuplicate() throws Exception {
        HttpResponse<String> first = addJob(client, "Archive Corp", "Archive Engineer");
        int applicationId = JsonPath.read(first.body(), "$.id");
        assertThat(client.patchJson("/api/applications/" + applicationId + "/status", "{\"statusCode\":\"ARCHIVED\"}")
                .statusCode()).isEqualTo(200);

        HttpResponse<String> again = addJob(client, "archive corp", "ARCHIVE ENGINEER");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(again.body(), "$.id")).isEqualTo(applicationId);
        assertThat(JsonPath.<String>read(again.body(), "$.status.code")).isEqualTo("ARCHIVED");
    }

    private static HttpResponse<String> addJob(TestApiClient api, String company, String title) throws Exception {
        return api.postJson("/api/board/jobs", "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}");
    }
}
