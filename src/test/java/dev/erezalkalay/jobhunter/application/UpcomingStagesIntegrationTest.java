package dev.erezalkalay.jobhunter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class UpcomingStagesIntegrationTest extends IntegrationTestBase {

    private static final String OTHER_EMAIL = "upcoming-other@jobhunter.local";
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
    void requiresAuthentication() throws Exception {
        assertThat(new TestApiClient(port).get("/api/stages/upcoming").statusCode()).isEqualTo(401);
    }

    @Test
    void returnsOnlyFutureScheduledStagesNearestFirstWithTheRowFields() throws Exception {
        int app = addJob("UpA Corp", "UpA Engineer");
        Instant now = Instant.now();
        addStage(app, "UpA later", now.plus(5, ChronoUnit.DAYS));
        addStage(app, "UpA soon", now.plus(2, ChronoUnit.HOURS));
        addStage(app, "UpA past", now.minus(2, ChronoUnit.HOURS));
        addStage(app, "UpA unscheduled", null);

        List<Map<String, Object>> mine = upcomingFor(app);

        assertThat(mine).extracting(r -> r.get("stageName")).containsExactly("UpA soon", "UpA later");
        Map<String, Object> first = mine.get(0);
        assertThat(first.keySet()).containsExactlyInAnyOrder(
                "stageId", "stageName", "scheduledAt", "applicationId", "jobId", "jobTitle", "companyName");
        assertThat(first.get("jobTitle")).isEqualTo("UpA Engineer");
        assertThat(first.get("companyName")).isEqualTo("UpA Corp");
        assertThat(first.get("applicationId")).isEqualTo(app);
        assertThat(Instant.parse((String) first.get("scheduledAt"))).isAfter(now);
    }

    @Test
    void wholeListIsSortedAscendingAcrossApplications() throws Exception {
        int a = addJob("UpB Corp", "UpB One");
        int b = addJob("UpB Corp", "UpB Two");
        Instant base = Instant.now().plus(30, ChronoUnit.DAYS);
        addStage(a, "UpB c", base.plus(3, ChronoUnit.HOURS));
        addStage(b, "UpB a", base.plus(1, ChronoUnit.HOURS));
        addStage(a, "UpB b", base.plus(2, ChronoUnit.HOURS));

        List<Instant> all = upcoming().stream().map(r -> Instant.parse((String) r.get("scheduledAt"))).toList();
        assertThat(all).isSorted();
        assertThat(upcomingFor(a, b)).extracting(r -> r.get("stageName")).containsExactly("UpB a", "UpB b", "UpB c");
    }

    @Test
    void equalTimestampsFallBackToStageIdOrder() throws Exception {
        int app = addJob("UpC Corp", "UpC Engineer");
        Instant same = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        int first = addStage(app, "UpC first", same);
        int second = addStage(app, "UpC second", same);

        assertThat(upcomingFor(app)).extracting(r -> r.get("stageId")).containsExactly(first, second);
    }

    @Test
    void stagesOfApplicationsInAnyStatusAreIncluded() throws Exception {
        int archived = addJob("UpD Corp", "UpD Archived");
        int lowMatch = addJob("UpD Corp", "UpD Low");
        addStage(archived, "UpD archived stage", Instant.now().plus(1, ChronoUnit.DAYS));
        addStage(lowMatch, "UpD low stage", Instant.now().plus(2, ChronoUnit.DAYS));
        client.patchJson("/api/applications/" + archived + "/status", "{\"statusCode\":\"ARCHIVED\"}");
        client.patchJson("/api/applications/" + lowMatch + "/status", "{\"statusCode\":\"LOW_MATCH\"}");

        assertThat(upcomingFor(archived, lowMatch)).extracting(r -> r.get("stageName"))
                .containsExactly("UpD archived stage", "UpD low stage");
    }

    @Test
    void onlyTheCurrentUsersStagesAreReturned() throws Exception {
        testData.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);

        int mine = addJob("UpE Corp", "UpE Engineer");
        addStage(mine, "UpE my stage", Instant.now().plus(1, ChronoUnit.DAYS));
        HttpResponse<String> theirs = other.postJson("/api/board/jobs", "{\"title\":\"UpE Engineer\",\"companyName\":\"UpE Corp\"}");
        int theirApp = JsonPath.read(theirs.body(), "$.id");
        other.postJson("/api/applications/" + theirApp + "/stages",
                "{\"name\":\"UpE their stage\",\"scheduledAt\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}");

        List<String> myNames = upcoming().stream().map(r -> (String) r.get("stageName")).toList();
        assertThat(myNames).contains("UpE my stage").doesNotContain("UpE their stage");
        List<String> theirNames = JsonPath.read(other.get("/api/stages/upcoming").body(), "$[*].stageName");
        assertThat(theirNames).contains("UpE their stage").doesNotContain("UpE my stage");
    }

    @Test
    void reflectsStageUpdatesAndDeletes() throws Exception {
        int app = addJob("UpF Corp", "UpF Engineer");
        int moved = addStage(app, "UpF moved", Instant.now().plus(1, ChronoUnit.DAYS));
        int deleted = addStage(app, "UpF deleted", Instant.now().plus(2, ChronoUnit.DAYS));
        addStage(app, "UpF stays", Instant.now().plus(3, ChronoUnit.DAYS));
        assertThat(upcomingFor(app)).hasSize(3);

        // A future stage moved into the past leaves the list; a deleted one too.
        assertThat(client.patchJson("/api/applications/" + app + "/stages/" + moved,
                "{\"name\":\"UpF moved\",\"scheduledAt\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}").statusCode()).isEqualTo(200);
        assertThat(client.deleteRequest("/api/applications/" + app + "/stages/" + deleted).statusCode()).isEqualTo(204);

        assertThat(upcomingFor(app)).extracting(r -> r.get("stageName")).containsExactly("UpF stays");

        // Deleting the application removes the rest.
        assertThat(client.deleteRequest("/api/applications/" + app).statusCode()).isEqualTo(204);
        assertThat(upcomingFor(app)).isEmpty();
    }

    private int addJob(String company, String title) throws Exception {
        HttpResponse<String> response = client.postJson("/api/board/jobs",
                "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}");
        assertThat(response.statusCode()).isIn(200, 201);
        return JsonPath.read(response.body(), "$.id");
    }

    private int addStage(int applicationId, String name, Instant scheduledAt) throws Exception {
        String json = "{\"name\":\"" + name + "\""
                + (scheduledAt == null ? "" : ",\"scheduledAt\":\"" + scheduledAt + "\"") + "}";
        HttpResponse<String> response = client.postJson("/api/applications/" + applicationId + "/stages", json);
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.id");
    }

    private List<Map<String, Object>> upcoming() throws Exception {
        HttpResponse<String> response = client.get("/api/stages/upcoming");
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$");
    }

    // The shared test database accumulates stages from other tests, so narrow to these applications.
    private List<Map<String, Object>> upcomingFor(int... applicationIds) throws Exception {
        return upcoming().stream()
                .filter(r -> java.util.Arrays.stream(applicationIds).anyMatch(id -> r.get("applicationId").equals(id)))
                .toList();
    }
}
