package dev.erezalkalay.jobhunter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The optional end time of a stage (scheduledEndAt): create/edit/remove, the
 * "strictly after the start" rule at the API, that other data is untouched, and
 * ownership. Runs against the isolated Testcontainers database only.
 */
class StageEndTimeIntegrationTest extends IntegrationTestBase {

    private static final String OTHER_EMAIL = "stage-end-other@jobhunter.local";
    private static final String OTHER_PASSWORD = "other-password-123";

    private static final String START = "2026-10-29T13:00:00Z";
    private static final String END = "2026-10-29T14:00:00Z";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestApiClient client;
    private long applicationId;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        HttpResponse<String> added = client.postJson("/api/board/jobs",
                "{\"title\":\"Stage End Role " + System.nanoTime() + "\",\"companyName\":\"Stage End Co\"}");
        assertThat(added.statusCode()).isEqualTo(201);
        applicationId = ((Number) JsonPath.read(added.body(), "$.id")).longValue();
        jobId = ((Number) JsonPath.read(added.body(), "$.jobId")).longValue();
    }

    // ===================== Create =====================

    @Test
    void aStageWithoutAnEndTimeStillWorksAndReportsNull() throws Exception {
        HttpResponse<String> created = createStage("{\"name\":\"Phone screen\",\"scheduledAt\":\"" + START + "\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(created.body(), "$.scheduledEndAt")).isNull();
        assertThat(JsonPath.<String>read(created.body(), "$.scheduledAt")).isEqualTo(START);
        // A stage with no times at all is still fine, as before.
        assertThat(createStage("{\"name\":\"Unscheduled\"}").statusCode()).isEqualTo(201);
        assertThat(JsonPath.<List<Object>>read(listStages(), "$[*].scheduledEndAt")).hasSize(2).containsOnlyNulls();
    }

    @Test
    void aStageWithAValidEndTimeIsCreatedAndSurvivesRefetch() throws Exception {
        HttpResponse<String> created = createStage(stageJson("Tech interview", START, END));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.scheduledEndAt")).isEqualTo(END);
        long id = idOf(created);
        assertThat(JsonPath.<List<String>>read(listStages(), "$[?(@.id==" + id + ")].scheduledEndAt")).containsExactly(END);
        assertThat(stageRow(id).get("scheduled_end_at")).isNotNull();
    }

    @Test
    void anEndEqualToTheStartIsRejectedAndNothingIsCreated() throws Exception {
        assertRejectedOnEnd(createStage(stageJson("Bad", START, START)));
        assertThat(JsonPath.<List<Object>>read(listStages(), "$")).isEmpty();
    }

    @Test
    void anEndBeforeTheStartIsRejectedAndNothingIsCreated() throws Exception {
        assertRejectedOnEnd(createStage(stageJson("Bad", START, "2026-10-29T12:59:59Z")));
        assertThat(JsonPath.<List<Object>>read(listStages(), "$")).isEmpty();
    }

    @Test
    void anEndWithoutAStartIsRejected() throws Exception {
        assertRejectedOnEnd(createStage("{\"name\":\"Bad\",\"scheduledEndAt\":\"" + END + "\"}"));
        assertThat(JsonPath.<List<Object>>read(listStages(), "$")).isEmpty();
    }

    // ===================== Edit =====================

    @Test
    void anEndTimeCanBeAddedChangedAndRemovedOnAnExistingStage() throws Exception {
        long id = idOf(createStage("{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"interviewerNames\":\"Dana\","
                + "\"notes\":\"Asked about Spring\",\"outcome\":\"Passed\"}"));

        HttpResponse<String> added = updateStage(id, stageJson("Interview", START, END));
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(added.body(), "$.scheduledEndAt")).isEqualTo(END);

        HttpResponse<String> changed = updateStage(id, stageJson("Interview", START, "2026-10-29T15:30:00Z"));
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.scheduledEndAt")).isEqualTo("2026-10-29T15:30:00Z");

        HttpResponse<String> removed = updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":null}");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(removed.body(), "$.scheduledEndAt")).isNull();
        assertThat(stageRow(id).get("scheduled_end_at")).isNull();
        assertThat(JsonPath.<Object>read(listStages(), "$[0].scheduledEndAt")).isNull();
    }

    @Test
    void changingTheStartKeepsAValidEndUnchanged() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));

        HttpResponse<String> moved = updateStage(id, stageJson("Interview", "2026-10-29T13:30:00Z", END));

        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(moved.body(), "$.scheduledAt")).isEqualTo("2026-10-29T13:30:00Z");
        assertThat(JsonPath.<String>read(moved.body(), "$.scheduledEndAt")).isEqualTo(END);
    }

    @Test
    void changingTheStartPastTheExistingEndIsRejectedAndNothingChanges() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));
        Map<String, Object> before = stageRow(id);

        // Start moved to the end (equal), and beyond it: the end is neither dropped nor adjusted.
        assertRejectedOnEnd(updateStage(id, stageJson("Interview", END, END)));
        assertRejectedOnEnd(updateStage(id, stageJson("Interview", "2026-10-29T16:00:00Z", END)));

        assertThat(stageRow(id)).isEqualTo(before);
        assertThat(JsonPath.<String>read(listStages(), "$[0].scheduledEndAt")).isEqualTo(END);
    }

    @Test
    void anInvalidEditOfAnExistingStageIsRejected() throws Exception {
        long id = idOf(createStage("{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\"}"));

        assertRejectedOnEnd(updateStage(id, stageJson("Interview", START, START)));
        assertRejectedOnEnd(updateStage(id, stageJson("Interview", START, "2026-10-29T09:00:00Z")));

        assertThat(stageRow(id).get("scheduled_end_at")).isNull();
    }

    // ===================== Omitted vs null vs value (PATCH three-state) =====================

    @Test
    void anOmittedEndTimeKeepsTheStoredEnd() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));

        HttpResponse<String> edited = updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\"}");

        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(edited.body(), "$.scheduledEndAt")).isEqualTo(END);
        assertThat(JsonPath.<String>read(listStages(), "$[0].scheduledEndAt")).isEqualTo(END);
        assertThat(stageRow(id).get("scheduled_end_at")).isNotNull();
    }

    @Test
    void anExplicitNullEndTimeRemovesTheStoredEnd() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));

        HttpResponse<String> edited = updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":null}");

        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(edited.body(), "$.scheduledEndAt")).isNull();
        assertThat(JsonPath.<Object>read(listStages(), "$[0].scheduledEndAt")).isNull();
        assertThat(stageRow(id).get("scheduled_end_at")).isNull();
    }

    @Test
    void aSuppliedEndTimeReplacesTheStoredEnd() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));

        HttpResponse<String> edited = updateStage(id, stageJson("Interview", START, "2026-10-29T16:00:00Z"));

        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(edited.body(), "$.scheduledEndAt")).isEqualTo("2026-10-29T16:00:00Z");
        assertThat(JsonPath.<String>read(listStages(), "$[0].scheduledEndAt")).isEqualTo("2026-10-29T16:00:00Z");
    }

    @Test
    void updatingAnyOtherFieldWithoutAnEndTimeCannotRemoveIt() throws Exception {
        long id = idOf(createStage("{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":\"" + END
                + "\",\"interviewerNames\":\"Dana\",\"notes\":\"n\",\"outcome\":\"Waiting\"}"));

        for (String change : new String[] {
                "\"name\":\"Renamed\",\"scheduledAt\":\"" + START + "\"",
                "\"name\":\"Renamed\",\"scheduledAt\":\"" + START + "\",\"outcome\":\"Passed\"",
                "\"name\":\"Renamed\",\"scheduledAt\":\"" + START + "\",\"notes\":\"new notes\"",
                "\"name\":\"Renamed\",\"scheduledAt\":\"2026-10-29T13:30:00Z\",\"interviewerNames\":\"Lee\""}) {
            HttpResponse<String> edited = updateStage(id, "{" + change + "}");
            assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
            assertThat(JsonPath.<String>read(edited.body(), "$.scheduledEndAt")).isEqualTo(END);
            assertThat(stageRow(id).get("scheduled_end_at")).isNotNull();
        }
    }

    @Test
    void anOmittedEndTimeIsStillCheckedAgainstTheNewStart() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));
        Map<String, Object> before = stageRow(id);

        // The kept end would no longer be after the start: refused, and the end is neither dropped nor moved.
        assertRejectedOnEnd(updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"" + END + "\"}"));
        assertRejectedOnEnd(updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"2026-10-29T18:00:00Z\"}"));
        // Omitting the start too would clear it while the end stays: refused, naming scheduledAt.
        HttpResponse<String> noStart = updateStage(id, "{\"name\":\"Interview\",\"outcome\":\"Passed\"}");
        assertThat(noStart.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(noStart.body(), "$.fieldErrors[*].field")).containsExactly("scheduledAt");

        assertThat(stageRow(id)).isEqualTo(before);
    }

    @Test
    void aMalformedEndTimeIsRejectedWithoutChangingTheStoredEnd() throws Exception {
        long id = idOf(createStage(stageJson("Interview", START, END)));

        assertThat(updateStage(id, "{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":\"tomorrow\"}").statusCode())
                .isEqualTo(400);

        assertThat(JsonPath.<String>read(listStages(), "$[0].scheduledEndAt")).isEqualTo(END);
    }

    @Test
    void anExplicitNullEndOnCreateIsSameAsNoEnd() throws Exception {
        HttpResponse<String> created = createStage("{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":null}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(created.body(), "$.scheduledEndAt")).isNull();
    }

    // ===================== Existing (end-less) stages =====================

    @Test
    void stagesWithoutAnEndTimeKeepWorkingLikeBefore() throws Exception {
        // A row exactly as it was stored before the column existed: no end value at all.
        jdbcTemplate.update("INSERT INTO application_stages (application_id, name, scheduled_at, interviewer_names, notes, outcome) "
                + "VALUES (?, 'Legacy stage', ?::timestamptz, 'Dana', 'old notes', 'Passed')", applicationId, START);
        long id = jdbcTemplate.queryForObject("SELECT id FROM application_stages WHERE application_id = ?", Long.class, applicationId);

        String listed = listStages();
        assertThat(JsonPath.<String>read(listed, "$[0].name")).isEqualTo("Legacy stage");
        assertThat(JsonPath.<String>read(listed, "$[0].scheduledAt")).isEqualTo(START);
        assertThat(JsonPath.<Object>read(listed, "$[0].scheduledEndAt")).isNull();

        // An old client that does not know the field edits it as before (start only).
        HttpResponse<String> edited = updateStage(id, "{\"name\":\"Legacy stage\",\"scheduledAt\":\"" + START
                + "\",\"interviewerNames\":\"Dana\",\"notes\":\"old notes\",\"outcome\":\"Offer\"}");
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(edited.body(), "$.outcome")).isEqualTo("Offer");
        assertThat(JsonPath.<Object>read(edited.body(), "$.scheduledEndAt")).isNull();
    }

    // ===================== Nothing else changes =====================

    @Test
    void updatingTheEndTimeChangesNothingButThatField() throws Exception {
        testData.createJobMatch(jobId, 66);
        client.patchJson("/api/applications/" + applicationId + "/notes", "{\"notes\":\"ticket notes\"}");
        client.patchJson("/api/applications/" + applicationId + "/starred", "{\"starred\":true}");
        client.patchJson("/api/applications/" + applicationId + "/status", "{\"statusCode\":\"CV_SENT\"}");
        long target = idOf(createStage("{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"interviewerNames\":\"Dana, Lee\","
                + "\"notes\":\"System design\",\"outcome\":\"Passed\"}"));
        long sibling = idOf(createStage(stageJson("Sibling", "2026-11-02T09:00:00Z", "2026-11-02T09:30:00Z")));

        Map<String, Object> before = snapshot(target, sibling);
        assertThat(updateStage(target, "{\"name\":\"Interview\",\"scheduledAt\":\"" + START + "\",\"scheduledEndAt\":\"" + END
                + "\",\"interviewerNames\":\"Dana, Lee\",\"notes\":\"System design\",\"outcome\":\"Passed\"}").statusCode()).isEqualTo(200);
        Map<String, Object> after = snapshot(target, sibling);

        assertThat(after).isEqualTo(before);
        assertThat(stageRow(target).get("scheduled_end_at")).isNotNull();
        String stages = listStages();
        assertThat(JsonPath.<List<String>>read(stages, "$[*].name")).containsExactly("Interview", "Sibling");
        assertThat(JsonPath.<String>read(stages, "$[0].interviewerNames")).isEqualTo("Dana, Lee");
        assertThat(JsonPath.<String>read(client.get("/api/applications/" + applicationId).body(), "$.notes")).isEqualTo("ticket notes");
    }

    // ===================== Ownership =====================

    @Test
    void anotherUserCannotReadOrSetTheEndTimeOfSomeoneElsesStage() throws Exception {
        long id = idOf(createStage(stageJson("Private interview", START, END)));
        testData.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);
        String path = "/api/applications/" + applicationId + "/stages";

        HttpResponse<String> listed = other.get(path);
        assertThat(listed.statusCode()).isEqualTo(404);
        assertThat(listed.body()).doesNotContain(END);
        assertThat(other.patchJson(path + "/" + id, stageJson("Private interview", START, "2026-10-29T20:00:00Z")).statusCode())
                .isEqualTo(404);
        assertThat(other.patchJson(path + "/" + id, "{\"name\":\"Private interview\",\"scheduledAt\":\"" + START + "\"}").statusCode())
                .isEqualTo(404);
        assertThat(other.postJson(path, stageJson("Injected", START, END)).statusCode()).isEqualTo(404);

        assertThat(JsonPath.<String>read(listStages(), "$[0].scheduledEndAt")).isEqualTo(END);
        assertThat(JsonPath.<List<Object>>read(listStages(), "$")).hasSize(1);
    }

    // ===================== helpers =====================

    private static String stageJson(String name, String start, String end) {
        return "{\"name\":\"" + name + "\",\"scheduledAt\":\"" + start + "\",\"scheduledEndAt\":\"" + end + "\"}";
    }

    private HttpResponse<String> createStage(String json) throws Exception {
        return client.postJson("/api/applications/" + applicationId + "/stages", json);
    }

    private HttpResponse<String> updateStage(long stageId, String json) throws Exception {
        return client.patchJson("/api/applications/" + applicationId + "/stages/" + stageId, json);
    }

    private String listStages() throws Exception {
        HttpResponse<String> response = client.get("/api/applications/" + applicationId + "/stages");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static long idOf(HttpResponse<String> response) {
        return ((Number) JsonPath.read(response.body(), "$.id")).longValue();
    }

    private static void assertRejectedOnEnd(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("scheduledEndAt");
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at dev.").doesNotContain("SQL");
    }

    private Map<String, Object> stageRow(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM application_stages WHERE id = ?", id);
    }

    // Everything stored about the ticket except the edited stage's end time and its audited updated_at.
    private Map<String, Object> snapshot(long editedStage, long siblingStage) {
        Map<String, Object> edited = stageRow(editedStage);
        edited.remove("scheduled_end_at");
        edited.remove("updated_at");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("editedStage", edited);
        snapshot.put("siblingStage", stageRow(siblingStage));
        snapshot.put("application", jdbcTemplate.queryForList("SELECT * FROM applications WHERE id = ?", applicationId));
        snapshot.put("job", jdbcTemplate.queryForList("SELECT * FROM jobs WHERE id = ?", jobId));
        snapshot.put("match", jdbcTemplate.queryForList("SELECT * FROM job_matches WHERE job_id = ?", jobId));
        snapshot.put("history", jdbcTemplate.queryForList(
                "SELECT * FROM application_status_history WHERE application_id = ? ORDER BY id", applicationId));
        return snapshot;
    }
}
