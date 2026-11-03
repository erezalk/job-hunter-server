package dev.erezalkalay.jobhunter.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Editing a job changes only the edited columns of its own jobs row (plus its
 * audited updated_at and, for a company change, the company reference): the AI
 * match, the Application, its stages and status history, and every other
 * user's rows are compared column by column before and after the edit.
 */
class JobEditPreservationIntegrationTest extends IntegrationTestBase {

    private static final String OTHER_EMAIL = "job-edit-preserve-other@jobhunter.local";
    private static final String OTHER_PASSWORD = "other-password-123";
    private static final AiMatchResult EVALUATION = new AiMatchResult(
            85, List.of("Java", "Spring Boot"), List.of("Kubernetes", "Go"), List.of("Long commute"));

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataSupport testDataSupport;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestApiClient client;
    private TestApiClient other;
    private String company;
    private long otherUserId;

    private record Ticket(long jobId, long applicationId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        testDataSupport.createUserIfMissing(OTHER_EMAIL, OTHER_PASSWORD);
        other = new TestApiClient(port);
        assertThat(other.login(OTHER_EMAIL, OTHER_PASSWORD).statusCode()).isEqualTo(200);
        otherUserId = testDataSupport.userId(OTHER_EMAIL);
        company = "Preserve Co " + System.nanoTime();
        fakeAiProviderState.reset();
        prepareAi();
        otherUsersTicket();
    }

    static Stream<Arguments> edits() {
        return Stream.of(
                Arguments.of("location only", Map.of("location", "Haifa"), Set.of("location")),
                Arguments.of("salary, currency, work model and URL",
                        Map.of("salaryMin", 200, "salaryMax", 250, "currency", "USD", "workModel", "REMOTE",
                                "url", "https://jobs.example.com/rich-v2"),
                        Set.of("salary_min", "salary_max", "currency", "work_model", "url", "normalized_url")),
                Arguments.of("description only", Map.of("description", "A completely rewritten description."),
                        Set.of("description")),
                Arguments.of("title only", Map.of("title", "Rich Role Renamed"), Set.of("title", "title_key")),
                Arguments.of("company only", Map.of("companyName", "%s Renamed"), Set.of("company_id", "company_key")),
                Arguments.of("source only", Map.of("source", "LinkedIn"), Set.of("source")),
                Arguments.of("salary cleared", Map.of("salaryMin", "", "salaryMax", ""),
                        Set.of("salary_min", "salary_max")),
                Arguments.of("every editable field at once",
                        Map.of("title", "All Changed", "companyName", "%s All", "location", "Eilat",
                                "workModel", "ONSITE", "url", "https://jobs.example.com/all", "salaryMin", 1,
                                "salaryMax", 2, "currency", "EUR", "description", "All new.", "source", "Friend"),
                        Set.of("title", "title_key", "company_id", "company_key", "location", "work_model", "url",
                                "normalized_url", "salary_min", "salary_max", "currency", "description", "source")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edits")
    void editChangesOnlyTheEditedFieldsAndKeepsEverythingElse(
            String name, Map<String, Object> changes, Set<String> changedJobColumns) throws Exception {
        Ticket ticket = richTicket();
        Map<String, Object> before = snapshot(ticket, changedJobColumns);
        Map<String, Object> jobRowBefore = jobRow(ticket);
        Map<String, Object> others = otherUserSnapshot();
        Map<String, Object> resolved = resolve(changes);

        HttpResponse<String> response = client.patchJson("/api/jobs/" + ticket.jobId(), editBody(ticket, resolved));

        assertThat(response.statusCode()).isEqualTo(200);
        // The edit was applied...
        Map<String, Object> jobRowAfter = jobRow(ticket);
        for (String column : changedJobColumns) {
            assertThat(jobRowAfter.get(column)).as(column).isNotEqualTo(jobRowBefore.get(column));
        }
        assertThat(((Timestamp) jobRowAfter.get("updated_at")).toInstant())
                .isAfterOrEqualTo(((Timestamp) jobRowBefore.get("updated_at")).toInstant());
        // ...and nothing else of this ticket or of anyone else changed.
        assertThat(snapshot(ticket, changedJobColumns)).isEqualTo(before);
        assertThat(otherUserSnapshot()).isEqualTo(others);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        assertPreservedThroughTheApi(ticket, response.body());
    }

    @Test
    void dedupeStillRejectsCollidingEditsAndLeavesTheTicketUntouched() throws Exception {
        Ticket ticket = richTicket();
        String otherCompany = company + " Elsewhere";
        assertThat(client.postJson("/api/board/jobs", "{\"title\":\"Taken Role\",\"companyName\":\"" + company + "\"}")
                .statusCode()).isEqualTo(201);
        assertThat(client.postJson("/api/board/jobs", "{\"title\":\"Rich Role\",\"companyName\":\"" + otherCompany + "\"}")
                .statusCode()).isEqualTo(201);
        Map<String, Object> before = snapshot(ticket, Set.of("updated_at"));
        Map<String, Object> jobRowBefore = jobRow(ticket);

        HttpResponse<String> titleClash = client.patchJson("/api/jobs/" + ticket.jobId(),
                editBody(ticket, Map.of("title", "  TAKEN   role ")));
        HttpResponse<String> companyClash = client.patchJson("/api/jobs/" + ticket.jobId(),
                editBody(ticket, Map.of("companyName", otherCompany.toUpperCase())));

        assertThat(titleClash.statusCode()).isEqualTo(409);
        assertThat(companyClash.statusCode()).isEqualTo(409);
        assertThat(jobRow(ticket)).isEqualTo(jobRowBefore);
        assertThat(snapshot(ticket, Set.of("updated_at"))).isEqualTo(before);

        // A successful rename moves the identity; re-adding under it deduplicates to this ticket and keeps its data.
        Map<String, Object> beforeRename = snapshot(ticket, Set.of("title", "title_key"));
        assertThat(client.patchJson("/api/jobs/" + ticket.jobId(), editBody(ticket, Map.of("title", "Fresh Role")))
                .statusCode()).isEqualTo(200);
        HttpResponse<String> readd = client.postJson("/api/board/jobs",
                "{\"title\":\"fresh  ROLE\",\"companyName\":\"" + company + "\",\"description\":\"Other text\"}");
        assertThat(readd.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(readd.body(), "$.jobId")).longValue()).isEqualTo(ticket.jobId());
        assertThat(snapshot(ticket, Set.of("title", "title_key"))).isEqualTo(beforeRename);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
    }

    @Test
    void anotherUserCannotEditTheTicketAndChangesNothing() throws Exception {
        Ticket ticket = richTicket();
        Map<String, Object> before = snapshot(ticket, Set.of());
        Map<String, Object> jobRowBefore = jobRow(ticket);
        String body = editBody(ticket, Map.of("location", "Hijacked"));

        assertThat(other.patchJson("/api/jobs/" + ticket.jobId(), body).statusCode()).isEqualTo(404);

        assertThat(jobRow(ticket)).isEqualTo(jobRowBefore);
        assertThat(snapshot(ticket, Set.of())).isEqualTo(before);
    }

    @Test
    void reEvaluateAfterAnEditIsTheOnlyThingThatReplacesTheStoredMatch() throws Exception {
        Ticket ticket = richTicket();
        assertThat(client.patchJson("/api/jobs/" + ticket.jobId(),
                editBody(ticket, Map.of("description", "Changed for the AI too."))).statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(client.get("/api/jobs/" + ticket.jobId() + "/match").body(), "$.fitScore"))
                .isEqualTo(85);

        fakeAiProviderState.setNextResult(new AiMatchResult(92, List.of("Kotlin"), List.of(), List.of()));
        HttpResponse<String> reEvaluated = client.postJson(
                "/api/jobs/" + ticket.jobId() + "/match/evaluate?force=true", "{}");

        assertThat(reEvaluated.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(2);
        HttpResponse<String> match = client.get("/api/jobs/" + ticket.jobId() + "/match");
        assertThat(JsonPath.<Integer>read(match.body(), "$.fitScore")).isEqualTo(92);
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).containsExactly("Kotlin");
    }

    // A ticket with every kind of data a user can attach to it.
    private Ticket richTicket() throws Exception {
        HttpResponse<String> created = client.postJson("/api/board/jobs", """
                {"title":"Rich Role","companyName":"%s","location":"Tel Aviv","workModel":"HYBRID",
                 "url":"https://jobs.example.com/rich","salaryMin":100,"salaryMax":150,"currency":"ILS",
                 "description":"Senior Java engineer, Spring Boot, Kubernetes."}""".formatted(company));
        assertThat(created.statusCode()).isEqualTo(201);
        Ticket ticket = new Ticket(((Number) JsonPath.read(created.body(), "$.jobId")).longValue(),
                ((Number) JsonPath.read(created.body(), "$.id")).longValue());
        assertThat(client.patchJson("/api/jobs/" + ticket.jobId(), editBody(ticket, Map.of("source", "Referral")))
                .statusCode()).isEqualTo(200);

        fakeAiProviderState.setNextResult(EVALUATION);
        assertThat(client.postJson("/api/jobs/" + ticket.jobId() + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);

        String applications = "/api/applications/" + ticket.applicationId();
        HttpResponse<String> neighbour = client.postJson("/api/board/jobs",
                "{\"title\":\"Neighbour Role\",\"companyName\":\"" + company + "\"}");
        long neighbourId = ((Number) JsonPath.read(neighbour.body(), "$.id")).longValue();
        assertThat(client.patchJson("/api/applications/" + neighbourId + "/status", "{\"statusCode\":\"CV_SENT\"}")
                .statusCode()).isEqualTo(200);
        assertThat(client.patchJson(applications + "/status", "{\"statusCode\":\"CV_SENT\"}").statusCode())
                .isEqualTo(200);
        assertThat(client.patchJson(applications + "/move",
                "{\"statusCode\":\"CV_SENT\",\"afterApplicationId\":" + neighbourId + "}").statusCode()).isEqualTo(200);
        assertThat(client.patchJson(applications + "/starred", "{\"starred\":true}").statusCode()).isEqualTo(200);
        assertThat(client.patchJson(applications + "/notes", "{\"notes\":\"Call the recruiter on Monday\"}")
                .statusCode()).isEqualTo(200);
        assertThat(client.postJson(applications + "/stages", """
                {"name":"Phone screen","scheduledAt":"2026-11-10T09:00:00Z","interviewerNames":"Dana",
                 "notes":"Asked about Spring","outcome":"Passed"}""").statusCode()).isEqualTo(201);
        assertThat(client.postJson(applications + "/stages", """
                {"name":"Tech interview","scheduledAt":"2026-11-20T12:30:00Z","interviewerNames":"Yossi, Noa",
                 "notes":"System design"}""").statusCode()).isEqualTo(201);
        return ticket;
    }

    // The other user has a ticket (with a match, notes and star) at a company of the very same name.
    private void otherUsersTicket() throws Exception {
        HttpResponse<String> created = other.postJson("/api/board/jobs", "{\"title\":\"Rich Role\",\"companyName\":\""
                + company + "\",\"location\":\"Tel Aviv\",\"description\":\"Their description\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        long jobId = ((Number) JsonPath.read(created.body(), "$.jobId")).longValue();
        long applicationId = ((Number) JsonPath.read(created.body(), "$.id")).longValue();
        testDataSupport.createJobMatch(jobId, 66);
        other.patchJson("/api/applications/" + applicationId + "/notes", "{\"notes\":\"their notes\"}");
        other.patchJson("/api/applications/" + applicationId + "/starred", "{\"starred\":true}");
    }

    // Everything stored about the ticket except the given jobs columns (and the jobs row's audited updated_at).
    private Map<String, Object> snapshot(Ticket ticket, Set<String> changedJobColumns) {
        Map<String, Object> job = jobRow(ticket);
        Set<String> skipped = new HashSet<>(changedJobColumns);
        skipped.add("updated_at");
        job.keySet().removeAll(skipped);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("job", job);
        snapshot.put("match", jdbcTemplate.queryForList("select * from job_matches where job_id = ?", ticket.jobId()));
        snapshot.put("application", jdbcTemplate.queryForList("select * from applications where id = ?",
                ticket.applicationId()));
        snapshot.put("stages", jdbcTemplate.queryForList(
                "select * from application_stages where application_id = ? order by id", ticket.applicationId()));
        snapshot.put("history", jdbcTemplate.queryForList(
                "select * from application_status_history where application_id = ? order by id", ticket.applicationId()));
        if (!changedJobColumns.contains("company_id")) {
            snapshot.put("company", jdbcTemplate.queryForList(
                    "select c.* from companies c join jobs j on j.company_id = c.id where j.id = ?", ticket.jobId()));
        }
        assertThat((List<?>) snapshot.get("match")).hasSize(1);
        return snapshot;
    }

    private Map<String, Object> jobRow(Ticket ticket) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("select * from jobs where id = ?", ticket.jobId()));
    }

    private Map<String, Object> otherUserSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String table : List.of("jobs", "companies", "applications", "job_matches")) {
            snapshot.put(table, jdbcTemplate.queryForList(
                    "select * from " + table + " where user_id = ? order by id", otherUserId));
        }
        snapshot.put("application_stages", jdbcTemplate.queryForList("select s.* from application_stages s "
                + "join applications a on a.id = s.application_id where a.user_id = ? order by s.id", otherUserId));
        snapshot.put("application_status_history", jdbcTemplate.queryForList("select h.* from application_status_history h "
                + "join applications a on a.id = h.application_id where a.user_id = ? order by h.id", otherUserId));
        return snapshot;
    }

    private void assertPreservedThroughTheApi(Ticket ticket, String editResponse) throws Exception {
        assertThat(((Number) JsonPath.read(editResponse, "$.fitScore")).intValue()).isEqualTo(85);
        String match = client.get("/api/jobs/" + ticket.jobId() + "/match").body();
        assertThat(JsonPath.<Integer>read(match, "$.fitScore")).isEqualTo(85);
        assertThat(JsonPath.<List<String>>read(match, "$.matchingSkills")).containsExactly("Java", "Spring Boot");
        assertThat(JsonPath.<List<String>>read(match, "$.missingSkills")).containsExactly("Kubernetes", "Go");
        assertThat(JsonPath.<List<String>>read(match, "$.concerns")).containsExactly("Long commute");
        assertThat(JsonPath.<String>read(match, "$.provider")).isEqualTo("ANTHROPIC");
        assertThat(JsonPath.<String>read(match, "$.model")).isEqualTo("test-model");
        assertThat(JsonPath.<String>read(match, "$.evaluatedAt")).isNotNull();

        String application = client.get("/api/applications/" + ticket.applicationId()).body();
        assertThat(JsonPath.<String>read(application, "$.status.code")).isEqualTo("CV_SENT");
        assertThat(JsonPath.<String>read(application, "$.notes")).isEqualTo("Call the recruiter on Monday");
        assertThat((Boolean) JsonPath.read(application, "$.starred")).isTrue();
        assertThat(JsonPath.<List<String>>read(client.get("/api/applications/" + ticket.applicationId()
                + "/status-history").body(), "$[*].status.code")).containsExactly("NEW", "CV_SENT");
        String stages = client.get("/api/applications/" + ticket.applicationId() + "/stages").body();
        assertThat(JsonPath.<List<String>>read(stages, "$[*].name")).containsExactly("Phone screen", "Tech interview");
        assertThat(JsonPath.<List<String>>read(stages, "$[*].outcome")).contains("Passed");

        List<Object> boardScores = JsonPath.read(client.get("/api/board").body(),
                "$[?(@.applicationId==" + ticket.applicationId() + ")].fitScore");
        assertThat(((Number) boardScores.get(0)).intValue()).isEqualTo(85);
    }

    // The same full-replace body the web edit form sends: every editable field
    // from the stored job (blank strings for nulls, salaries omitted when null), then the changes.
    private String editBody(Ticket ticket, Map<String, Object> changes) throws Exception {
        String job = client.get("/api/jobs/" + ticket.jobId()).body();
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : List.of("title", "companyName", "location", "workModel", "url", "currency",
                "description", "source")) {
            Object value = JsonPath.read(job, "$." + field);
            body.put(field, value == null ? "" : value);
        }
        for (String field : List.of("salaryMin", "salaryMax")) {
            Object value = JsonPath.read(job, "$." + field);
            if (value != null) {
                body.put(field, value);
            }
        }
        changes.forEach((field, value) -> {
            if ("".equals(value) && field.startsWith("salary")) {
                body.remove(field);
            } else {
                body.put(field, value);
            }
        });
        return jsonMapper.writeValueAsString(body);
    }

    private Map<String, Object> resolve(Map<String, Object> changes) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        changes.forEach((field, value) -> resolved.put(field,
                value instanceof String text && text.contains("%s") ? text.formatted(company) : value));
        return resolved;
    }

    private void prepareAi() throws Exception {
        User user = userRepository.findByEmailIgnoreCase("test@jobhunter.local").orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
        assertThat(client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Erez Alkalay - Senior Java Engineer with 10 years of Spring Boot.")).statusCode())
                .isEqualTo(201);
        HttpResponse<String> current = client.get("/api/settings");
        List<String> visible = JsonPath.read(current.body(), "$.visibleBoardStatuses");
        String visibleJson = "[" + String.join(",", visible.stream().map(v -> "\"" + v + "\"").toList()) + "]";
        assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + visibleJson + ",\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}")
                .statusCode()).isEqualTo(200);
        assertThat(client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}")
                .statusCode()).isEqualTo(200);
    }
}
