package dev.erezalkalay.jobhunter.jobdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiMatchResult;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Job data export/import (format v1) over real HTTP against the isolated
 * Testcontainers database. Every test works with its own freshly created user
 * and uniquely named jobs, so each starts from an empty Board.
 */
class JobDataExportImportIntegrationTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CV_TEXT = "Portable Person - Senior Java Engineer, Spring Boot, PostgreSQL, Kubernetes.";
    private static final String EXPORT = "/api/job-data/export";
    private static final String IMPORT = "/api/job-data/import";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private FakeAiProviderState fakeAi;

    private String run;
    private TestApiClient client;
    private String email;

    @BeforeEach
    void setUp() throws Exception {
        run = System.nanoTime() + "-" + SEQ.incrementAndGet();
        email = "jobdata-" + run + "@jobhunter.local";
        client = login(email);
        fakeAi.reset();
    }

    // ===================== Round trip =====================

    @Test
    void exportThenRestoreIntoAnEmptyBoardReproducesTheSameLogicalData() throws Exception {
        Rich rich = buildRichBoard();
        String first = exportBody(client);
        assertRichExportContent(first, rich);
        List<Long> oldApplicationIds = boardApplicationIds(client);

        // Empty the Board (the restore workflow: fresh/empty Job/Board domain).
        for (long id : oldApplicationIds) {
            assertThat(client.deleteRequest("/api/applications/" + id).statusCode()).isEqualTo(204);
        }
        assertThat(boardApplicationIds(client)).isEmpty();
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title LIKE ?", "%" + run + "%")).isZero();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE user_id = ?", testData.userId(email))).isZero();

        HttpResponse<String> imported = client.postJson(IMPORT, first);
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(imported.body(), "$.jobs")).isEqualTo(rich.jobCount);
        assertThat((Integer) JsonPath.read(imported.body(), "$.stages")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(imported.body(), "$.aiMatches")).isEqualTo(3);

        String second = exportBody(client);
        assertThat(withoutExportedAt(second)).isEqualTo(withoutExportedAt(first));

        // New database identities, same logical data.
        List<Long> newApplicationIds = boardApplicationIds(client);
        assertThat(newApplicationIds).hasSize(oldApplicationIds.size()).doesNotContainAnyElementsOf(oldApplicationIds);
        // History restored exactly, not re-generated (the restore adds no rows of its own).
        assertThat(JsonPath.<List<Object>>read(second, "$.jobs[*].application.statusHistory[*]"))
                .hasSize(JsonPath.<List<Object>>read(first, "$.jobs[*].application.statusHistory[*]").size());
        // Restored matches carry no CV link and no reuse fingerprint (both pointed at the old database).
        assertThat(testData.count("SELECT count(*) FROM job_matches m JOIN users u ON u.id = m.user_id "
                + "WHERE u.email = ? AND (m.fingerprint IS NOT NULL OR m.cv_id IS NOT NULL)", email)).isZero();
        assertThat(testData.count("SELECT count(*) FROM job_matches m JOIN users u ON u.id = m.user_id WHERE u.email = ?", email))
                .isEqualTo(3);
    }

    @Test
    void aRestoredMatchIsShownButNeverReusedAsACacheHit() throws Exception {
        configureAi(client);
        long jobId = addJob(client, "Cache Role " + run, "Acme " + run, "Java backend work.");
        fakeAi.setNextResult(new AiMatchResult(88, List.of("Java"), List.of(), List.of()));
        assertThat(client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        String export = exportBody(client);
        deleteBoard(client);
        assertThat(client.postJson(IMPORT, export).statusCode()).isEqualTo(201);
        long restoredJob = ((Number) JsonPath.read(client.get("/api/board").body(), "$[0].jobId")).longValue();
        assertThat((Integer) JsonPath.read(client.get("/api/jobs/" + restoredJob + "/match").body(), "$.fitScore")).isEqualTo(88);
        fakeAi.reset();

        // A non-forced evaluation with the same CV/settings must call the provider: the old fingerprint is gone.
        assertThat(client.postJson("/api/jobs/" + restoredJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAi.callCount()).isEqualTo(1);
    }

    @Test
    void theExportFormatCoversEveryPersistedJobAndBoardColumn() {
        // Fails when a column is added to a job/Board table without deciding how
        // the export treats it (see JobDataV1 and PROJECT_STATUS.md).
        Map<String, Set<String>> classified = new LinkedHashMap<>();
        // Owner columns (user_id) are never exported: the importing session user becomes the owner.
        classified.put("jobs", Set.of(
                // exported
                "title", "description", "description_discarded", "location", "work_model", "url", "salary_min", "salary_max",
                "currency", "source", "external_id", "discovered_at", "created_at", "updated_at",
                // expressed by nesting (job.company)
                "company_id",
                // recomputed on import
                "normalized_url", "company_key", "title_key",
                // database identity / owner
                "id", "user_id"));
        classified.put("companies", Set.of(
                // exported
                "name", "website", "notes",
                // recomputed on import
                "name_key",
                // database identity / owner / internal bookkeeping (not shown anywhere)
                "id", "user_id", "created_at", "updated_at"));
        classified.put("applications", Set.of("status_code", "board_position", "starred", "notes", "applied_at",
                "created_at", "updated_at", "id", "job_id", "user_id"));
        classified.put("application_status_history", Set.of("status_code", "entered_at", "notes", "id", "application_id"));
        classified.put("application_stages", Set.of("name", "scheduled_at", "interviewer_names", "notes", "outcome",
                "created_at", "updated_at", "id", "application_id"));
        classified.put("job_matches", Set.of("match_score", "matching_skills", "missing_skills", "concerns", "provider",
                "model", "evaluated_at", "created_at", "id", "job_id", "user_id", "cv_id", "fingerprint"));

        for (Map.Entry<String, Set<String>> table : classified.entrySet()) {
            List<String> columns = testData.strings(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                    table.getKey());
            assertThat(new TreeSet<>(columns)).as(table.getKey()).isEqualTo(new TreeSet<>(table.getValue()));
        }
    }

    // ===================== Export =====================

    @Test
    void exportAndImportRequireALoggedInBrowserSession() throws Exception {
        TestApiClient anonymous = new TestApiClient(port);
        assertThat(anonymous.get(EXPORT).statusCode()).isEqualTo(401);
        assertThat(anonymous.postJson(IMPORT, validFile(job("a", "Anonymous Role " + run))).statusCode()).isEqualTo(401);
    }

    @Test
    void anEmptyBoardExportsAnEmptyVersionOneFileAsAJsonAttachment() throws Exception {
        HttpResponse<String> response = client.get(EXPORT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).matches("attachment; filename=\"job-hunter-export-\\d{4}-\\d{2}-\\d{2}\\.json\""));
        assertThat(JsonPath.<String>read(response.body(), "$.format")).isEqualTo("job-hunter-jobs");
        assertThat((Integer) JsonPath.read(response.body(), "$.formatVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(response.body(), "$.exportedAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.jobs")).isEmpty();
    }

    @Test
    void exportContainsOnlyTheCurrentUsersBoardAndNoCvSettingsAccountOrSecrets() throws Exception {
        // This user: a CV, settings, an AI key and an MCP token — none of which may appear.
        configureAi(client);
        String token = JsonPath.read(client.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        String sameTitle = "Same Role " + run;
        addJob(client, sameTitle, "Same Co " + run, "My description");

        // Another user tracks the same company + title (their own job) with their own data, and another job.
        String otherEmail = "jobdata-other-" + run + "@jobhunter.local";
        TestApiClient other = login(otherEmail);
        HttpResponse<String> otherSame = other.postJson("/api/board/jobs",
                "{\"title\":\"" + sameTitle + "\",\"companyName\":\"Same Co " + run + "\",\"description\":\"OTHER USER DESCRIPTION\"}");
        assertThat(otherSame.statusCode()).isEqualTo(201);
        long otherApplication = ((Number) JsonPath.read(otherSame.body(), "$.id")).longValue();
        other.patchJson("/api/applications/" + otherApplication + "/notes", "{\"notes\":\"OTHER USER PRIVATE NOTE\"}");
        other.postJson("/api/applications/" + otherApplication + "/stages", "{\"name\":\"OTHER USER STAGE\"}");
        other.patchJson("/api/applications/" + otherApplication + "/status", "{\"statusCode\":\"OFFER\",\"notes\":\"OTHER USER HISTORY\"}");
        addJob(other, "Other Only Role " + run, "Other Co " + run, "Other description");

        String export = exportBody(client);

        assertThat(JsonPath.<List<String>>read(export, "$.jobs[*].title")).containsExactly(sameTitle);
        assertThat(JsonPath.<String>read(export, "$.jobs[0].application.status")).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(export, "$.jobs[0].description")).isEqualTo("My description");
        assertThat(export).doesNotContain("OTHER USER").doesNotContain("Other Only Role").doesNotContain("OFFER")
                .doesNotContain(CV_TEXT).doesNotContain("cv.pdf").doesNotContain(FakeAiProviderState.VALID_API_KEY)
                .doesNotContain(token).doesNotContain(email)
                .doesNotContain(otherEmail).doesNotContain("Europe/Berlin").doesNotContain("test-model")
                .doesNotContain("password").doesNotContain("fingerprint").doesNotContain("visibleBoardStatuses")
                .doesNotContain("minimumFitScore").doesNotContain("theme");
        assertNoDatabaseIds(JSON.readTree(export));
    }

    // ===================== Import: rejections change nothing =====================

    @Test
    void importIntoABoardThatAlreadyHasJobsIsRejectedAndChangesNothing() throws Exception {
        addJob(client, "Existing Role " + run, "Existing Co " + run, "d");
        String before = exportBody(client);

        HttpResponse<String> response = client.postJson(IMPORT, validFile(job("a", "Incoming Role " + run)));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("empty Board");
        assertThat(withoutExportedAt(exportBody(client))).isEqualTo(withoutExportedAt(before));
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title = ?", "Incoming Role " + run)).isZero();
    }

    @Test
    void anotherUsersJobsWithTheSameCompanyAndTitleNeitherBlockTheImportNorChange() throws Exception {
        String title = "Taken Role " + run;
        TestApiClient other = login("jobdata-owner-" + run + "@jobhunter.local");
        long otherJob = addJob(other, title, "Taken Co " + run, "Their description");
        String otherBefore = exportBody(other);

        HttpResponse<String> response = client.postJson(IMPORT, validFile(
                job("a", "Free Role " + run), withCompany(job("b", title), "taken co " + run)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(exportBody(client), "$.jobs[*].title")).containsExactlyInAnyOrder("Free Role " + run, title);
        // Their job, company and Board are exactly as before; mine is a separate row.
        assertThat(withoutExportedAt(exportBody(other))).isEqualTo(withoutExportedAt(otherBefore));
        assertThat(JsonPath.<String>read(other.get("/api/jobs/" + otherJob).body(), "$.description")).isEqualTo("Their description");
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title = ?", title)).isEqualTo(2);
        assertThat(testData.count("SELECT count(DISTINCT user_id) FROM companies WHERE name_key = ?", "taken co " + run)).isEqualTo(2);
    }

    @Test
    void jobsNamingTheSameCompanyMustCarryTheSameCompanyData() throws Exception {
        ObjectNode first = withCompany(job("a", "First Co Role " + run), "Consistent Co " + run);
        ObjectNode second = withCompany(job("b", "Second Co Role " + run), "consistent  co " + run);
        ((ObjectNode) second.get("company")).put("notes", "different notes");

        HttpResponse<String> response = client.postJson(IMPORT, validFile(first, second));

        assertRejected(response, 400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("jobs[1].company");
        assertNothingImported();
    }

    @Test
    void theMcpBearerTokenCannotExportOrImport() throws Exception {
        String token = JsonPath.read(client.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> export = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + EXPORT))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> imported = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + IMPORT))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(validFile(job("a", "Token Role " + run)))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(export.statusCode()).isEqualTo(401);
        assertThat(imported.statusCode()).isIn(401, 403);
        assertNothingImported();
    }

    @Test
    void malformedOrWronglyTypedFilesAreRejected() throws Exception {
        assertRejected(client.postJson(IMPORT, "{ this is not json"), 400);
        assertRejected(client.postJson(IMPORT, ""), 400);
        assertRejected(client.postJson(IMPORT, "[]"), 400);
        assertRejected(client.postJson(IMPORT, validFile(job("a", "Trailing " + run)) + " {}"), 400);
        assertRejected(client.send("POST", IMPORT, "text/plain", validFile(job("a", "Plain " + run))), 415);

        ObjectNode wrongType = job("a", "Typed Role " + run);
        wrongType.put("salaryMin", "100000");
        assertRejected(client.postJson(IMPORT, validFile(wrongType)), 400);
        ObjectNode fraction = job("a", "Fraction Role " + run);
        fraction.put("salaryMin", 1.5);
        assertRejected(client.postJson(IMPORT, validFile(fraction)), 400);
        ObjectNode badDate = job("a", "Date Role " + run);
        badDate.put("discoveredAt", "yesterday");
        assertRejected(client.postJson(IMPORT, validFile(badDate)), 400);
        ObjectNode boolTitle = job("a", "Bool Role " + run);
        boolTitle.put("title", true);
        assertRejected(client.postJson(IMPORT, validFile(boolTitle)), 400);
        ObjectNode numberNotes = job("a", "Number Notes " + run);
        ((ObjectNode) numberNotes.get("application")).put("notes", 42);
        assertRejected(client.postJson(IMPORT, validFile(numberNotes)), 400);
        ObjectNode stringFlag = job("a", "String Flag " + run);
        stringFlag.put("descriptionDiscarded", "false");
        assertRejected(client.postJson(IMPORT, validFile(stringFlag)), 400);
        assertNothingImported();
    }

    @Test
    void unsupportedFormatOrVersionIsRejected() throws Exception {
        ObjectNode v2 = (ObjectNode) JSON.readTree(validFile(job("a", "Version Role " + run)));
        v2.put("formatVersion", 2);
        HttpResponse<String> response = client.postJson(IMPORT, v2.toString());
        assertRejected(response, 400);
        assertThat(JsonPath.<String>read(response.body(), "$.fieldErrors[0].message")).contains("unsupported version 2");

        ObjectNode otherFormat = (ObjectNode) JSON.readTree(validFile(job("a", "Format Role " + run)));
        otherFormat.put("format", "something-else");
        assertRejected(client.postJson(IMPORT, otherFormat.toString()), 400);
        assertNothingImported();
    }

    @Test
    void structuralAndDomainViolationsAreReportedByLocationAndNothingIsWritten() throws Exception {
        ObjectNode noApplication = job("c", "No Application " + run);
        noApplication.remove("application");
        ObjectNode badStatus = job("d", "Bad Status " + run);
        ((ObjectNode) badStatus.get("application")).put("status", "OUTREACH");
        ObjectNode badHistory = job("e", "Bad History " + run);
        ((ObjectNode) badHistory.get("application").get("statusHistory").get(0)).put("status", "NOPE");
        ObjectNode longTitle = job("f", "x".repeat(256));
        ObjectNode badUrl = job("g", "Bad Url " + run);
        badUrl.put("url", "javascript:alert(1)");
        ObjectNode badProvider = withMatch(job("h", "Bad Provider " + run));
        ((ObjectNode) badProvider.get("aiMatch")).put("provider", "OPENAI");
        ObjectNode badScore = withMatch(job("i", "Bad Score " + run));
        ((ObjectNode) badScore.get("aiMatch")).put("fitScore", 101);
        ObjectNode salaries = job("j", "Salaries " + run);
        salaries.put("salaryMin", 200).put("salaryMax", 100);
        ObjectNode missingTimestamp = job("k", "Missing Time " + run);
        missingTimestamp.putNull("createdAt");
        ObjectNode sameIdentity = job("l", "SAME  identity " + run);

        HttpResponse<String> response = client.postJson(IMPORT, validFile(
                job("a", "Valid Role " + run), job("a", "Duplicate Ref " + run), noApplication, badStatus, badHistory,
                longTitle, badUrl, badProvider, badScore, salaries, missingTimestamp, job("m", "same identity " + run),
                sameIdentity));

        assertRejected(response, 400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).contains(
                "jobs[1].ref", "jobs[2].application", "jobs[3].application.status",
                "jobs[4].application.statusHistory[0].status", "jobs[5].title", "jobs[6].url", "jobs[7].aiMatch.provider",
                "jobs[8].aiMatch.fitScore", "jobs[9].salaryMin", "jobs[10].createdAt", "jobs[12].title");
        assertNothingImported();
    }

    @Test
    void theFileCannotNameAnOwnerAndUnknownFieldsAreRejected() throws Exception {
        ObjectNode withOwner = job("a", "Owner Role " + run);
        withOwner.put("userId", 1);
        assertRejected(client.postJson(IMPORT, validFile(withOwner)), 400);
        ObjectNode file = (ObjectNode) JSON.readTree(validFile(job("a", "Owner Role 2 " + run)));
        file.put("owner", "someone-else@jobhunter.local");
        assertRejected(client.postJson(IMPORT, file.toString()), 400);
        assertNothingImported();
    }

    @Test
    void aFailureHalfwayThroughRollsBackTheWholeImport() throws Exception {
        // Passes validation, but PostgreSQL refuses NUL characters — only when the last job is written.
        ObjectNode poisoned = job("c", "Poisoned Role " + run);
        ((ArrayNode) poisoned.get("application").get("stages")).addObject()
                .put("name", "Interview").put("notes", "bad \u0000 byte")
                .put("createdAt", "2025-03-01T10:00:00Z").put("updatedAt", "2025-03-01T10:00:00Z");
        String companyName = "Rollback Co " + run;

        HttpResponse<String> response = client.postJson(IMPORT, validFile(
                withCompany(job("a", "First Role " + run), companyName), withMatch(job("b", "Second Role " + run)), poisoned));

        assertThat(response.statusCode()).isBetween(400, 499);
        assertThat(response.body()).doesNotContain("SQL").doesNotContain("Exception");
        assertNothingImported();
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE title LIKE ?", "%" + run + "%")).isZero();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE name = ?", companyName)).isZero();
    }

    @Test
    void aValidImportRestoresRelationshipsAndHistoricalTimestampsForTheSessionUserOnly() throws Exception {
        configureAi(client);
        String settingsBefore = client.get("/api/settings").body();
        String cvBefore = client.get("/api/cv").body();
        String tokenBefore = client.get("/api/settings/integrations/mcp-token").body();
        ObjectNode stagedJob = job("a", "Restored Role " + run);
        ((ObjectNode) stagedJob.get("application")).put("status", "INTERVIEW_PROCESS").put("starred", true)
                .put("boardPosition", 3).put("notes", "restored notes").put("appliedAt", "2025-02-02T09:00:00Z");
        ((ArrayNode) stagedJob.get("application").get("statusHistory")).addObject()
                .put("status", "INTERVIEW_PROCESS").put("enteredAt", "2025-02-10T09:00:00Z").putNull("notes");
        ((ArrayNode) stagedJob.get("application").get("stages")).addObject()
                .put("name", "Tech Interview").put("scheduledAt", "2025-02-20T13:30:00Z").put("interviewerNames", "Dana, Lee")
                .put("notes", "system design").put("outcome", "Passed")
                .put("createdAt", "2025-02-11T08:00:00Z").put("updatedAt", "2025-02-21T08:00:00Z");

        HttpResponse<String> response = client.postJson(IMPORT, validFile(stagedJob, withMatch(job("b", "Matched Role " + run))));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<Long> applications = boardApplicationIds(client);
        assertThat(applications).hasSize(2);
        long restored = applications.stream().filter(id -> {
            try {
                return JsonPath.<String>read(client.get("/api/applications/" + id).body(), "$.jobTitle").startsWith("Restored");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
        String application = client.get("/api/applications/" + restored).body();
        assertThat(JsonPath.<String>read(application, "$.status.code")).isEqualTo("INTERVIEW_PROCESS");
        assertThat((Boolean) JsonPath.read(application, "$.starred")).isTrue();
        assertThat(JsonPath.<String>read(application, "$.notes")).isEqualTo("restored notes");
        assertThat(JsonPath.<String>read(application, "$.appliedAt")).isEqualTo("2025-02-02T09:00:00Z");
        assertThat(JsonPath.<String>read(application, "$.createdAt")).isEqualTo("2025-01-15T10:00:00Z");
        assertThat(JsonPath.<List<String>>read(client.get("/api/applications/" + restored + "/status-history").body(),
                "$[*].enteredAt")).containsExactly("2025-01-15T10:00:00Z", "2025-02-10T09:00:00Z");
        String stages = client.get("/api/applications/" + restored + "/stages").body();
        assertThat(JsonPath.<List<String>>read(stages, "$[*].interviewerNames")).containsExactly("Dana, Lee");
        assertThat(JsonPath.<List<String>>read(stages, "$[*].createdAt")).containsExactly("2025-02-11T08:00:00Z");
        long jobId = ((Number) JsonPath.read(application, "$.jobId")).longValue();
        assertThat(JsonPath.<String>read(client.get("/api/jobs/" + jobId).body(), "$.discoveredAt")).isEqualTo("2025-01-10T08:00:00Z");
        assertThat(testData.count("SELECT count(*) FROM applications a JOIN users u ON u.id = a.user_id WHERE a.id = ? AND u.email = ?",
                restored, email)).isEqualTo(1);
        // CV, settings and integration token are untouched.
        assertThat(client.get("/api/settings").body()).isEqualTo(settingsBefore);
        assertThat(client.get("/api/cv").body()).isEqualTo(cvBefore);
        assertThat(client.get("/api/settings/integrations/mcp-token").body()).isEqualTo(tokenBefore);
    }

    @Test
    void importingSomeoneElsesFileCreatesIndependentCopiesAndNeverTouchesTheOriginals() throws Exception {
        long mineJob = addJob(client, "Mine Only " + run, "Mine Co " + run, "my description");
        String mine = exportBody(client);
        TestApiClient other = login("jobdata-intruder-" + run + "@jobhunter.local");

        assertThat(exportBody(other)).doesNotContain("Mine Only");
        // The file cannot attach them to my jobs: the restore creates their own rows.
        HttpResponse<String> response = other.postJson(IMPORT, mine);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        List<Number> theirJobs = JsonPath.read(other.get("/api/board").body(), "$[*].jobId");
        assertThat(theirJobs).hasSize(1);
        assertThat(theirJobs.get(0).longValue()).isNotEqualTo(mineJob);
        assertThat(other.get("/api/jobs/" + mineJob).statusCode()).isEqualTo(404);

        // Their later edits and deletion never reach my job.
        long theirJob = theirJobs.get(0).longValue();
        assertThat(other.patchJson("/api/jobs/" + theirJob, "{\"title\":\"Renamed By Them\",\"companyName\":\"Mine Co " + run
                + "\",\"description\":\"their text\"}").statusCode()).isEqualTo(200);
        assertThat(other.deleteRequest("/api/applications/" + boardApplicationIds(other).get(0)).statusCode()).isEqualTo(204);
        assertThat(withoutExportedAt(exportBody(client))).isEqualTo(withoutExportedAt(mine));
    }

    // ===================== Rich data set =====================

    private record Rich(int jobCount, String lowTitle, String archivedTitle) {
    }

    private Rich buildRichBoard() throws Exception {
        configureAi(client);
        String company = "Rich Co " + run;

        // 1. Interview process, starred, notes, stages, a match above the threshold, salary, URL, edited source.
        long backend = addJob(client, "Backend Engineer " + run, company, "Build Java services.");
        long backendApp = applicationOf(backend);
        assertThat(client.patchJson("/api/jobs/" + backend, "{\"title\":\"Backend Engineer " + run + "\",\"companyName\":\""
                + company + "\",\"description\":\"Build Java services.\",\"location\":\"Tel Aviv\",\"workModel\":\"Hybrid\","
                + "\"url\":\"https://jobs.example.com/backend?ref=1\",\"salaryMin\":30000,\"salaryMax\":42000,"
                + "\"currency\":\"ILS\",\"source\":\"Recruiter referral\"}").statusCode()).isEqualTo(200);
        client.patchJson("/api/applications/" + backendApp + "/status", "{\"statusCode\":\"CV_SENT\",\"notes\":\"sent tailored CV\"}");
        client.patchJson("/api/applications/" + backendApp + "/status", "{\"statusCode\":\"INTERVIEW_PROCESS\"}");
        client.patchJson("/api/applications/" + backendApp + "/starred", "{\"starred\":true}");
        client.patchJson("/api/applications/" + backendApp + "/notes", "{\"notes\":\"Salary discussion: flexible.\\nRecruiter: Noa\"}");
        client.postJson("/api/applications/" + backendApp + "/stages", "{\"name\":\"Recruiter Call\",\"scheduledAt\":\"2025-04-01T09:00:00Z\","
                + "\"interviewerNames\":\"Noa\",\"notes\":\"Intro\",\"outcome\":\"Passed\"}");
        client.postJson("/api/applications/" + backendApp + "/stages", "{\"name\":\"System Design\",\"interviewerNames\":\"Dana, Lee\"}");
        fakeAi.setNextResult(new AiMatchResult(85, List.of("Java", "Spring Boot"), List.of("Go"), List.of("Hybrid 3 days")));
        assertThat(client.postJson("/api/jobs/" + backend + "/match/evaluate", "{}").statusCode()).isEqualTo(200);

        // 2. Low match: automatic transition, description discarded.
        String lowTitle = "Frontend Designer " + run;
        long low = addJob(client, lowTitle, company, "Figma heavy role.");
        fakeAi.setNextResult(new AiMatchResult(20, List.of("CSS"), List.of("Figma"), List.of("Design focus")));
        assertThat(client.postJson("/api/jobs/" + low + "/match/evaluate", "{}").statusCode()).isEqualTo(200);

        // 3-5. Archived, offer, rejected (with a stage).
        String archivedTitle = "Archived Role " + run;
        setStatus(addJob(client, archivedTitle, "Archive Co " + run, null), "ARCHIVED");
        setStatus(addJob(client, "Offer Role " + run, "Offer Co " + run, "Great offer"), "OFFER");
        long rejected = addJob(client, "Rejected Role " + run, "Reject Co " + run, "Nope");
        setStatus(rejected, "REJECTED");
        client.postJson("/api/applications/" + applicationOf(rejected) + "/stages",
                "{\"name\":\"Final\",\"scheduledAt\":\"2025-05-05T12:00:00Z\",\"outcome\":\"Rejected\",\"notes\":\"no feedback\"}");

        // 6. Added through MCP with an imported (External AI) fit.
        String token = JsonPath.read(client.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        HttpResponse<String> mcp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/integrations/jobs"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Platform Engineer " + run + "\",\"companyName\":\"Mcp Co " + run
                        + "\",\"location\":\"Remote\",\"fitScore\":92,\"matchingSkills\":[\"Kubernetes\"],\"missingSkills\":[],"
                        + "\"concerns\":[\"On-call\"],\"evaluationModel\":\"Claude Opus 5.5\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(201);

        // 7-8. Two NEW tickets without company, explicitly reordered.
        long first = addJob(client, "No Company One " + run, null, null);
        long second = addJob(client, "No Company Two " + run, null, null);
        assertThat(client.patchJson("/api/applications/" + applicationOf(first) + "/move",
                "{\"statusCode\":\"NEW\",\"beforeApplicationId\":" + applicationOf(second) + "}").statusCode()).isEqualTo(200);

        // Fields no endpoint sets, and historical timestamps (isolated test database only).
        testData.update("UPDATE jobs SET external_id = ?, discovered_at = ?, created_at = ?, updated_at = ? WHERE id = ?",
                "ext-" + run, at("2025-01-10T08:00:00Z"), at("2025-01-10T08:00:00Z"), at("2025-03-01T08:00:00Z"), backend);
        testData.update("UPDATE companies SET website = ?, notes = ? WHERE name = ?", "https://rich.example.com", "Company notes", company);
        testData.update("UPDATE applications SET created_at = ? WHERE id = ?", at("2025-01-15T10:00:00Z"), backendApp);
        testData.update("UPDATE application_status_history SET entered_at = ? WHERE application_id = ? AND status_code = 'NEW'",
                at("2025-01-15T10:00:00Z"), backendApp);
        testData.update("UPDATE job_matches SET evaluated_at = ?, created_at = ? WHERE job_id = ?",
                at("2025-03-02T11:00:00Z"), at("2025-03-02T11:00:00Z"), backend);
        return new Rich(8, lowTitle, archivedTitle);
    }

    private void assertRichExportContent(String export, Rich rich) {
        String backend = "$.jobs[?(@.title == 'Backend Engineer " + run + "')]";
        assertThat(JsonPath.<List<String>>read(export, backend + ".company.website")).containsExactly("https://rich.example.com");
        assertThat(JsonPath.<List<String>>read(export, backend + ".company.notes")).containsExactly("Company notes");
        assertThat(JsonPath.<List<String>>read(export, backend + ".url")).containsExactly("https://jobs.example.com/backend?ref=1");
        assertThat(JsonPath.<List<String>>read(export, backend + ".location")).containsExactly("Tel Aviv");
        assertThat(JsonPath.<List<String>>read(export, backend + ".workModel")).containsExactly("Hybrid");
        assertThat(JsonPath.<List<Integer>>read(export, backend + ".salaryMin")).containsExactly(30000);
        assertThat(JsonPath.<List<Integer>>read(export, backend + ".salaryMax")).containsExactly(42000);
        assertThat(JsonPath.<List<String>>read(export, backend + ".currency")).containsExactly("ILS");
        assertThat(JsonPath.<List<String>>read(export, backend + ".source")).containsExactly("Recruiter referral");
        assertThat(JsonPath.<List<String>>read(export, backend + ".externalId")).containsExactly("ext-" + run);
        assertThat(JsonPath.<List<String>>read(export, backend + ".discoveredAt")).containsExactly("2025-01-10T08:00:00Z");
        assertThat(JsonPath.<List<String>>read(export, backend + ".updatedAt")).containsExactly("2025-03-01T08:00:00Z");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.status")).containsExactly("INTERVIEW_PROCESS");
        assertThat(JsonPath.<List<Boolean>>read(export, backend + ".application.starred")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.notes")).containsExactly("Salary discussion: flexible.\nRecruiter: Noa");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.appliedAt")).hasSize(1).doesNotContainNull();
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.createdAt")).containsExactly("2025-01-15T10:00:00Z");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.statusHistory[*].status"))
                .containsExactly("NEW", "CV_SENT", "INTERVIEW_PROCESS");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.statusHistory[*].notes")).contains("sent tailored CV");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.stages[*].name")).containsExactly("Recruiter Call", "System Design");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.stages[*].scheduledAt")).contains("2025-04-01T09:00:00Z");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.stages[*].interviewerNames")).containsExactly("Noa", "Dana, Lee");
        assertThat(JsonPath.<List<String>>read(export, backend + ".application.stages[*].outcome")).contains("Passed");
        assertThat(JsonPath.<List<Number>>read(export, backend + ".aiMatch.fitScore").get(0).doubleValue()).isEqualTo(85.0);
        assertThat(JsonPath.<List<List<String>>>read(export, backend + ".aiMatch.matchingSkills")).containsExactly(List.of("Java", "Spring Boot"));
        assertThat(JsonPath.<List<List<String>>>read(export, backend + ".aiMatch.missingSkills")).containsExactly(List.of("Go"));
        assertThat(JsonPath.<List<List<String>>>read(export, backend + ".aiMatch.concerns")).containsExactly(List.of("Hybrid 3 days"));
        assertThat(JsonPath.<List<String>>read(export, backend + ".aiMatch.provider")).containsExactly("ANTHROPIC");
        assertThat(JsonPath.<List<String>>read(export, backend + ".aiMatch.model")).containsExactly("test-model");
        assertThat(JsonPath.<List<String>>read(export, backend + ".aiMatch.evaluatedAt")).containsExactly("2025-03-02T11:00:00Z");

        String low = "$.jobs[?(@.title == '" + rich.lowTitle() + "')]";
        assertThat(JsonPath.<List<String>>read(export, low + ".application.status")).containsExactly("LOW_MATCH");
        assertThat(JsonPath.<List<Boolean>>read(export, low + ".descriptionDiscarded")).containsExactly(true);
        assertThat(JsonPath.<List<Object>>read(export, low + ".description")).containsExactly((Object) null);
        assertThat(JsonPath.<List<String>>read(export, low + ".application.statusHistory[*].notes"))
                .anySatisfy(note -> assertThat(note).startsWith("Automatic: low AI match"));

        String mcp = "$.jobs[?(@.title == 'Platform Engineer " + run + "')]";
        assertThat(JsonPath.<List<String>>read(export, mcp + ".source")).containsExactly("External AI");
        assertThat(JsonPath.<List<String>>read(export, mcp + ".aiMatch.provider")).containsExactly("EXTERNAL_AI");
        assertThat(JsonPath.<List<String>>read(export, mcp + ".aiMatch.model")).containsExactly("Claude Opus 5.5");

        assertThat(JsonPath.<List<String>>read(export, "$.jobs[*].application.status"))
                .contains("NEW", "INTERVIEW_PROCESS", "LOW_MATCH", "ARCHIVED", "OFFER", "REJECTED");
        assertThat(JsonPath.<List<Object>>read(export, "$.jobs[?(@.company == null)]")).hasSize(2);
        List<Integer> newPositions = JsonPath.read(export, "$.jobs[?(@.title =~ /No Company.*/)].application.boardPosition");
        assertThat(newPositions).doesNotHaveDuplicates();
        assertThat(JsonPath.<List<String>>read(export, "$.jobs[*].ref")).doesNotHaveDuplicates().allMatch(ref -> ref.startsWith("job-"));
    }

    // ===================== Helpers =====================

    private TestApiClient login(String userEmail) throws Exception {
        testData.createUserIfMissing(userEmail, "jobdata-password-123");
        TestApiClient api = new TestApiClient(port);
        assertThat(api.login(userEmail, "jobdata-password-123").statusCode()).isEqualTo(200);
        return api;
    }

    private void configureAi(TestApiClient api) throws Exception {
        assertThat(api.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf", TestFiles.pdfWithText(CV_TEXT))
                .statusCode()).isEqualTo(201);
        assertThat(api.putJson("/api/settings", "{\"theme\":\"DARK\",\"timezone\":\"Europe/Berlin\",\"visibleBoardStatuses\":[\"NEW\"],"
                + "\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(api.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\""
                + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode()).isEqualTo(200);
    }

    private long addJob(TestApiClient api, String title, String company, String description) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"" + title + "\"");
        if (company != null) {
            body.append(",\"companyName\":\"").append(company).append('"');
        }
        if (description != null) {
            body.append(",\"description\":\"").append(description).append('"');
        }
        HttpResponse<String> response = api.postJson("/api/board/jobs", body.append('}').toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return ((Number) JsonPath.read(response.body(), "$.jobId")).longValue();
    }

    private long applicationOf(long jobId) throws Exception {
        List<Number> ids = JsonPath.read(client.get("/api/board").body(), "$[?(@.jobId == " + jobId + ")].applicationId");
        return ids.get(0).longValue();
    }

    private void setStatus(long jobId, String status) throws Exception {
        assertThat(client.patchJson("/api/applications/" + applicationOf(jobId) + "/status",
                "{\"statusCode\":\"" + status + "\"}").statusCode()).isEqualTo(200);
    }

    private List<Long> boardApplicationIds(TestApiClient api) throws Exception {
        List<Number> ids = JsonPath.read(api.get("/api/board").body(), "$[*].applicationId");
        return ids.stream().map(Number::longValue).toList();
    }

    private void deleteBoard(TestApiClient api) throws Exception {
        for (long id : boardApplicationIds(api)) {
            assertThat(api.deleteRequest("/api/applications/" + id).statusCode()).isEqualTo(204);
        }
    }

    private String exportBody(TestApiClient api) throws Exception {
        HttpResponse<String> response = api.get(EXPORT);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private void assertRejected(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at dev.").doesNotContain("SQL");
    }

    private void assertNothingImported() throws Exception {
        assertThat(boardApplicationIds(client)).isEmpty();
        assertThat(testData.count("SELECT count(*) FROM job_matches m JOIN users u ON u.id = m.user_id WHERE u.email = ?", email)).isZero();
    }

    private static JsonNode withoutExportedAt(String export) {
        ObjectNode node = (ObjectNode) JSON.readTree(export);
        node.remove("exportedAt");
        return node;
    }

    private static void assertNoDatabaseIds(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                assertThat(field.getKey()).isNotIn("id", "jobId", "applicationId", "userId", "companyId", "cvId", "user", "email");
                assertNoDatabaseIds(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(JobDataExportImportIntegrationTest::assertNoDatabaseIds);
        }
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    /** A minimal valid job entry (application in NEW with one history row). */
    private static ObjectNode job(String ref, String title) {
        ObjectNode job = JSON.createObjectNode();
        job.put("ref", ref).put("title", title).putNull("company").putNull("description").put("descriptionDiscarded", false)
                .putNull("location").putNull("workModel").putNull("url").putNull("salaryMin").putNull("salaryMax")
                .putNull("currency").put("source", "Manual").putNull("externalId")
                .put("discoveredAt", "2025-01-10T08:00:00Z").put("createdAt", "2025-01-10T08:00:00Z")
                .put("updatedAt", "2025-01-12T08:00:00Z");
        ObjectNode application = job.putObject("application");
        application.put("status", "NEW").put("boardPosition", 0).put("starred", false).putNull("notes").putNull("appliedAt")
                .put("createdAt", "2025-01-15T10:00:00Z").put("updatedAt", "2025-01-15T10:00:00Z");
        application.putArray("statusHistory").addObject()
                .put("status", "NEW").put("enteredAt", "2025-01-15T10:00:00Z").put("notes", "Application created");
        application.putArray("stages");
        job.putNull("aiMatch");
        return job;
    }

    private static ObjectNode withCompany(ObjectNode job, String name) {
        job.putObject("company").put("name", name).putNull("website").putNull("notes");
        return job;
    }

    private static ObjectNode withMatch(ObjectNode job) {
        ObjectNode match = job.putObject("aiMatch");
        match.put("fitScore", 77).put("provider", "GEMINI").put("model", "gemini-model")
                .put("evaluatedAt", "2025-01-20T10:00:00Z").put("createdAt", "2025-01-20T10:00:00Z");
        match.putArray("matchingSkills").add("Java");
        match.putArray("missingSkills");
        match.putArray("concerns");
        return job;
    }

    private static String validFile(ObjectNode... jobs) {
        ObjectNode file = JSON.createObjectNode();
        file.put("format", "job-hunter-jobs").put("formatVersion", 1).put("exportedAt", "2025-06-01T00:00:00Z");
        ArrayNode array = file.putArray("jobs");
        new ArrayList<>(List.of(jobs)).forEach(array::add);
        return file.toString();
    }
}
