package dev.erezalkalay.jobhunter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ApplicationFlowIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    private TestApiClient client;

    @BeforeEach
    void setUp() {
        client = new TestApiClient(port);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        HttpResponse<String> response = client.get("/api/board");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        HttpResponse<String> response = client.login("test@jobhunter.local", "wrong-password");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void fullJobToApplicationLifecycleWithArbitraryTransitions() throws Exception {
        login();

        String jobJson = """
                {"title":"Backend Engineer","companyName":"Acme Corp","location":"Remote","workModel":"Remote","salaryMin":90000,"salaryMax":120000,"currency":"USD"}
                """;
        // A job is always created together with its Board ticket.
        HttpResponse<String> createApplication = client.postJson("/api/board/jobs", jobJson);
        assertThat(createApplication.statusCode()).isEqualTo(201);
        Integer applicationId = JsonPath.read(createApplication.body(), "$.id");
        Integer jobId = JsonPath.read(createApplication.body(), "$.jobId");
        assertThat(JsonPath.<String>read(createApplication.body(), "$.status.code")).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + jobId).body(), "$.companyName")).isEqualTo("Acme Corp");

        HttpResponse<String> statuses = client.get("/api/application-statuses");
        assertThat(statuses.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(statuses.body(), "$[*].code"))
                .containsExactly("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER", "REJECTED", "ARCHIVED", "LOW_MATCH");

        HttpResponse<String> advance = client.patchJson(
                "/api/applications/" + applicationId + "/status",
                "{\"statusCode\":\"CV_SENT\",\"notes\":\"Sent tailored CV\"}");
        assertThat(advance.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(advance.body(), "$.status.code")).isEqualTo("CV_SENT");
        assertThat((String) JsonPath.read(advance.body(), "$.appliedAt")).isNotNull();

        // Dropping a card back onto its current column is a harmless no-op, not a 409.
        HttpResponse<String> sameStatusAgain = client.patchJson(
                "/api/applications/" + applicationId + "/status",
                "{\"statusCode\":\"CV_SENT\"}");
        assertThat(sameStatusAgain.statusCode()).isEqualTo(200);

        HttpResponse<String> unknownStatus = client.patchJson(
                "/api/applications/" + applicationId + "/status",
                "{\"statusCode\":\"NOT_A_REAL_STATUS\"}");
        assertThat(unknownStatus.statusCode()).isEqualTo(409);

        // Arbitrary movement, including jumping straight to a terminal status
        // and back out of it again.
        HttpResponse<String> toRejected = client.patchJson(
                "/api/applications/" + applicationId + "/status", "{\"statusCode\":\"REJECTED\"}");
        assertThat(toRejected.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(toRejected.body(), "$.status.code")).isEqualTo("REJECTED");

        HttpResponse<String> backToInterview = client.patchJson(
                "/api/applications/" + applicationId + "/status", "{\"statusCode\":\"INTERVIEW_PROCESS\"}");
        assertThat(backToInterview.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(backToInterview.body(), "$.status.code")).isEqualTo("INTERVIEW_PROCESS");

        HttpResponse<String> history = client.get("/api/applications/" + applicationId + "/status-history");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(history.body(), "$[*].status.code"))
                .containsExactly("NEW", "CV_SENT", "REJECTED", "INTERVIEW_PROCESS");

        HttpResponse<String> applications = client.get("/api/applications");
        assertThat(applications.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(applications.body(), "$[*].id")).contains(applicationId);
    }

    @Test
    void applicationNotesCanBeUpdated() throws Exception {
        login();
        Integer applicationId = createJobAndApplication("Notes Test Role", "Notes Co");

        HttpResponse<String> update = client.patchJson(
                "/api/applications/" + applicationId + "/notes",
                "{\"notes\":\"Recruiter said range is 85-95k. Hybrid 2 days/week.\"}");
        assertThat(update.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(update.body(), "$.notes")).isEqualTo("Recruiter said range is 85-95k. Hybrid 2 days/week.");

        HttpResponse<String> reload = client.get("/api/applications/" + applicationId);
        assertThat(JsonPath.<String>read(reload.body(), "$.notes")).contains("Hybrid 2 days/week");
    }

    @Test
    void applicationStagesCanBeCreatedUpdatedAndDeleted() throws Exception {
        login();
        Integer applicationId = createJobAndApplication("Stage Test Role", "Stage Co");

        HttpResponse<String> create = client.postJson(
                "/api/applications/" + applicationId + "/stages",
                "{\"name\":\"Technical Interview\",\"interviewerNames\":\"Alice, Bob\",\"notes\":\"Java concurrency\"}");
        assertThat(create.statusCode()).isEqualTo(201);
        Integer stageId = JsonPath.read(create.body(), "$.id");
        assertThat(JsonPath.<String>read(create.body(), "$.name")).isEqualTo("Technical Interview");

        HttpResponse<String> second = client.postJson(
                "/api/applications/" + applicationId + "/stages",
                "{\"name\":\"System Design\",\"scheduledAt\":\"2026-10-10T11:00:00Z\"}");
        assertThat(second.statusCode()).isEqualTo(201);

        HttpResponse<String> list = client.get("/api/applications/" + applicationId + "/stages");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$[*].name")).containsExactlyInAnyOrder("Technical Interview", "System Design");

        HttpResponse<String> update = client.patchJson(
                "/api/applications/" + applicationId + "/stages/" + stageId,
                "{\"name\":\"Technical Interview\",\"outcome\":\"Passed\"}");
        assertThat(update.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(update.body(), "$.outcome")).isEqualTo("Passed");

        HttpResponse<String> delete = client.deleteRequest("/api/applications/" + applicationId + "/stages/" + stageId);
        assertThat(delete.statusCode()).isEqualTo(204);

        HttpResponse<String> listAfterDelete = client.get("/api/applications/" + applicationId + "/stages");
        List<Integer> remainingStageIds = JsonPath.read(listAfterDelete.body(), "$[*].id");
        assertThat(remainingStageIds).doesNotContain(stageId);
    }

    @Test
    void boardReturnsAllApplicationsForCurrentUser() throws Exception {
        login();
        Integer applicationId = createJobAndApplication("Board Role", "Board Co");

        HttpResponse<String> board = client.get("/api/board");
        assertThat(board.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(board.body(), "$[*].applicationId")).contains(applicationId);
        assertThat(JsonPath.<List<String>>read(board.body(), "$[*].statusCode")).contains("NEW");
    }

    @Test
    void addAndTrackJobIsIdempotentForTheSameCompanyAndTitle() throws Exception {
        login();

        String request = """
                {"title":"Platform Engineer","companyName":"Dedup Inc","url":"https://jobs.example.com/123?utm=abc"}
                """;
        HttpResponse<String> first = client.postJson("/api/board/jobs", request);
        assertThat(first.statusCode()).isEqualTo(201);
        Integer firstApplicationId = JsonPath.read(first.body(), "$.id");
        Integer firstJobId = JsonPath.read(first.body(), "$.jobId");

        String sameUrlDifferentQuery = """
                {"title":"Platform Engineer","companyName":"Dedup Inc","url":"https://jobs.example.com/123"}
                """;
        HttpResponse<String> second = client.postJson("/api/board/jobs", sameUrlDifferentQuery);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(second.body(), "$.id")).isEqualTo(firstApplicationId);
        assertThat((Integer) JsonPath.read(second.body(), "$.jobId")).isEqualTo(firstJobId);

        HttpResponse<String> board = client.get("/api/board");
        long matchingCards = JsonPath.<List<Integer>>read(board.body(), "$[*].jobId").stream()
                .filter(id -> id.equals(firstJobId))
                .count();
        assertThat(matchingCards).isEqualTo(1);
    }

    private void login() throws Exception {
        HttpResponse<String> login = client.login("test@jobhunter.local", "test-password-123");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(login.body(), "$.email")).isEqualTo("test@jobhunter.local");
    }

    private Integer createJobAndApplication(String title, String company) throws Exception {
        String jobJson = "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}";
        HttpResponse<String> createApplication = client.postJson("/api/board/jobs", jobJson);
        assertThat(createApplication.statusCode()).isEqualTo(201);
        return JsonPath.read(createApplication.body(), "$.id");
    }
}
