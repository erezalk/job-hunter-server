package dev.erezalkalay.jobhunter.isolation;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Multi-user isolation matrix: two fresh users per test, A and B. User A's data
 * (job, company, ticket, stage, history, AI match, CV, settings, token) must be
 * neither readable nor changeable through user B — whatever ids B guesses or
 * crafts — and B's own activity on an identical company + title never has a
 * side effect on A. "Nothing changed" is checked against A's full export plus
 * the drawer endpoints, so any stray write would show up.
 */
class MultiUserIsolationIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "isolation-password-123";
    private static final String COMPANY = "Example GmbH";
    private static final String TITLE = "Senior Backend Engineer";
    private static final String A_DESCRIPTION = "A's private description: Java, Spring Boot, PostgreSQL.";
    private static final String A_CV_TEXT = "Alice Private CV - Staff Java Engineer, Spring Boot, PostgreSQL, Kafka.";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private FakeAiProviderState fakeAi;

    private String run;
    private TestApiClient a;
    private TestApiClient b;
    private long aJob;
    private long aApplication;
    private long aStage;

    @BeforeEach
    void setUp() throws Exception {
        run = System.nanoTime() + "-" + SEQ.incrementAndGet();
        fakeAi.reset();
        a = login("isolation-a-" + run + "@jobhunter.local");
        b = login("isolation-b-" + run + "@jobhunter.local");

        HttpResponse<String> added = a.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, A_DESCRIPTION));
        assertThat(added.statusCode()).isEqualTo(201);
        aJob = id(added, "$.jobId");
        aApplication = id(added, "$.id");
        assertThat(a.patchJson(app(aApplication) + "/notes", "{\"notes\":\"A private notes\"}").statusCode()).isEqualTo(200);
        assertThat(a.patchJson(app(aApplication) + "/status", "{\"statusCode\":\"CV_SENT\",\"notes\":\"A history note\"}")
                .statusCode()).isEqualTo(200);
        HttpResponse<String> stage = a.postJson(app(aApplication) + "/stages", "{\"name\":\"A Interview\",\"scheduledAt\":\""
                + Instant.now().plus(3, ChronoUnit.DAYS) + "\",\"interviewerNames\":\"A interviewer\",\"notes\":\"A stage notes\"}");
        assertThat(stage.statusCode()).isEqualTo(201);
        aStage = id(stage, "$.id");
    }

    // ===================== Jobs =====================

    @Test
    void bCannotReadEditOrDeleteAsJob() throws Exception {
        String before = snapshotOfA();

        HttpResponse<String> read = b.get("/api/jobs/" + aJob);
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.body()).doesNotContain(A_DESCRIPTION).doesNotContain(TITLE);
        assertThat(b.patchJson("/api/jobs/" + aJob, jobBody("Hijacked", "Evil Co", "overwritten")).statusCode()).isEqualTo(404);
        assertThat(b.deleteRequest(app(aApplication)).statusCode()).isEqualTo(404);
        assertThat(b.send("DELETE", "/api/jobs/" + aJob, null, null).statusCode()).isIn(404, 405);

        assertThat(snapshotOfA()).isEqualTo(before);
    }

    @Test
    void bTracksTheSameCompanyAndTitleIndependentlyAndNothingBDoesReachesA() throws Exception {
        String before = snapshotOfA();

        HttpResponse<String> theirs = b.postJson("/api/board/jobs", jobBody(TITLE.toUpperCase(), "  example   gmbh ", "B's description"));
        assertThat(theirs.statusCode()).isEqualTo(201);
        long bJob = id(theirs, "$.jobId");
        long bApplication = id(theirs, "$.id");
        assertThat(bJob).isNotEqualTo(aJob);
        assertThat(bApplication).isNotEqualTo(aApplication);

        // Every mutable job field, the company, status, star, notes, stages, order — then delete.
        assertThat(b.patchJson("/api/jobs/" + bJob, "{\"title\":\"B Renamed\",\"companyName\":\"Example GmbH\","
                + "\"description\":\"B edited\",\"location\":\"Munich\",\"workModel\":\"Onsite\",\"url\":\"https://b.example.com/job\","
                + "\"salaryMin\":1,\"salaryMax\":2,\"currency\":\"$\",\"source\":\"B source\"}").statusCode()).isEqualTo(200);
        assertThat(b.patchJson(app(bApplication) + "/status", "{\"statusCode\":\"OFFER\"}").statusCode()).isEqualTo(200);
        assertThat(b.patchJson(app(bApplication) + "/starred", "{\"starred\":true}").statusCode()).isEqualTo(200);
        assertThat(b.patchJson(app(bApplication) + "/notes", "{\"notes\":\"B notes\"}").statusCode()).isEqualTo(200);
        assertThat(b.postJson(app(bApplication) + "/stages", "{\"name\":\"B stage\"}").statusCode()).isEqualTo(201);
        assertThat(b.patchJson(app(bApplication) + "/move", "{\"statusCode\":\"NEW\"}").statusCode()).isEqualTo(200);
        assertThat(snapshotOfA()).isEqualTo(before);

        assertThat(b.deleteRequest(app(bApplication)).statusCode()).isEqualTo(204);
        assertThat(snapshotOfA()).isEqualTo(before);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE user_id = ?", userId(a))).isEqualTo(1);
    }

    // ===================== Applications / Board =====================

    @Test
    void bCannotReadOrChangeAsTicket() throws Exception {
        String before = snapshotOfA();

        assertThat(b.get(app(aApplication)).statusCode()).isEqualTo(404);
        assertThat(b.get("/api/applications").body()).doesNotContain(TITLE);
        assertThat(b.get("/api/applications?status=CV_SENT").body()).doesNotContain(TITLE);
        assertThat(b.patchJson(app(aApplication) + "/status", "{\"statusCode\":\"REJECTED\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/notes", "{\"notes\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/starred", "{\"starred\":true}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/move", "{\"statusCode\":\"ARCHIVED\"}").statusCode()).isEqualTo(404);
        // There is no endpoint that attaches a user to an existing job.
        assertThat(b.postJson("/api/jobs/" + aJob + "/applications", "{}").statusCode()).isEqualTo(404);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE job_id = ?", aJob)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE user_id = ?", userId(b))).isZero();

        assertThat(snapshotOfA()).isEqualTo(before);
    }

    @Test
    void bCannotUseAsTicketAsAMoveAnchorAndBothBoardsKeepTheirOwnOrder() throws Exception {
        long a2 = id(a.postJson("/api/board/jobs", jobBody("A Second", COMPANY, null)), "$.id");
        long b1 = id(b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, null)), "$.id");
        long b2 = id(b.postJson("/api/board/jobs", jobBody("B Second", COMPANY, null)), "$.id");
        assertThat(a.patchJson(app(aApplication) + "/move", "{\"statusCode\":\"NEW\",\"afterApplicationId\":" + a2 + "}")
                .statusCode()).isEqualTo(200);
        List<Long> aOrder = order(a);
        String before = snapshotOfA();

        HttpResponse<String> crafted = b.patchJson(app(b1) + "/move", "{\"statusCode\":\"NEW\",\"beforeApplicationId\":" + aApplication + "}");
        assertThat(crafted.statusCode()).isEqualTo(400);
        assertThat(b.patchJson(app(b2) + "/move", "{\"statusCode\":\"NEW\",\"afterApplicationId\":" + b1 + "}").statusCode())
                .isEqualTo(200);

        assertThat(order(a)).isEqualTo(aOrder);
        assertThat(order(b)).containsExactly(b1, b2);
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== Stages / history =====================

    @Test
    void bCannotReadCreateUpdateOrDeleteAsStagesOrHistory() throws Exception {
        long bApplication = id(b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, null)), "$.id");
        String before = snapshotOfA();

        HttpResponse<String> stages = b.get(app(aApplication) + "/stages");
        assertThat(stages.statusCode()).isEqualTo(404);
        assertThat(stages.body()).doesNotContain("A Interview");
        assertThat(b.postJson(app(aApplication) + "/stages", "{\"name\":\"Injected\"}").statusCode()).isEqualTo(404);
        assertThat(b.patchJson(app(aApplication) + "/stages/" + aStage, "{\"name\":\"Renamed\"}").statusCode()).isEqualTo(404);
        assertThat(b.deleteRequest(app(aApplication) + "/stages/" + aStage).statusCode()).isEqualTo(404);
        // Nested IDOR: B's own (valid) application with A's stage id.
        assertThat(b.patchJson(app(bApplication) + "/stages/" + aStage, "{\"name\":\"Renamed\"}").statusCode()).isEqualTo(404);
        assertThat(b.deleteRequest(app(bApplication) + "/stages/" + aStage).statusCode()).isEqualTo(404);
        HttpResponse<String> history = b.get(app(aApplication) + "/status-history");
        assertThat(history.statusCode()).isEqualTo(404);
        assertThat(history.body()).doesNotContain("A history note");
        // The upcoming-stages bell only ever lists the caller's own stages.
        assertThat(b.get("/api/stages/upcoming").body()).doesNotContain("A Interview").doesNotContain("\"stageId\":" + aStage + ",");
        assertThat(JsonPath.<List<Integer>>read(a.get("/api/stages/upcoming").body(), "$[*].stageId")).contains((int) aStage);

        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== AI matching =====================

    @Test
    void bCannotEvaluateOrSeeAsMatchAndBsLowMatchNeverTouchesA() throws Exception {
        configureAi(a, A_CV_TEXT);
        fakeAi.setNextResult(new AiMatchResult(85, List.of("Java"), List.of("Go"), List.of("Hybrid")));
        assertThat(a.postJson("/api/jobs/" + aJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        configureAi(b, "Bob CV - Junior designer, Figma, illustration.");
        long bJob = id(b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, "B description")), "$.jobId");
        fakeAi.reset();
        String before = snapshotOfA();

        for (String force : List.of("", "?force=true")) {
            assertThat(b.postJson("/api/jobs/" + aJob + "/match/evaluate" + force, "{}").statusCode()).isEqualTo(404);
        }
        HttpResponse<String> match = b.get("/api/jobs/" + aJob + "/match");
        assertThat(match.statusCode()).isEqualTo(404);
        assertThat(match.body()).doesNotContain("Hybrid");
        assertThat(fakeAi.callCount()).isZero();

        // B's own identical job evaluated far below the threshold: LOW_MATCH and discard for B only.
        fakeAi.setNextResult(new AiMatchResult(5, List.of(), List.of("Java"), List.of()));
        assertThat(b.postJson("/api/jobs/" + bJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAi.callCount()).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(b.get("/api/jobs/" + bJob).body(), "$.descriptionDiscarded")).isTrue();

        assertThat(snapshotOfA()).isEqualTo(before);
        assertThat(JsonPath.<String>read(a.get(app(aApplication)).body(), "$.status.code")).isEqualTo("CV_SENT");
        assertThat(JsonPath.<String>read(a.get("/api/jobs/" + aJob).body(), "$.description")).isEqualTo(A_DESCRIPTION);
        assertThat((Integer) JsonPath.read(a.get("/api/jobs/" + aJob + "/match").body(), "$.fitScore")).isEqualTo(85);
    }

    @Test
    void theMatchCacheNeverCrossesUsersEvenForIdenticalInputs() throws Exception {
        // Same CV text, same provider/model, same job content: still two separate evaluations.
        configureAi(a, A_CV_TEXT);
        configureAi(b, A_CV_TEXT);
        long bJob = id(b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, A_DESCRIPTION)), "$.jobId");

        fakeAi.setNextResult(new AiMatchResult(90, List.of("A skill"), List.of(), List.of()));
        assertThat(a.postJson("/api/jobs/" + aJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(a.postJson("/api/jobs/" + aJob + "/match/evaluate", "{}").statusCode()).isEqualTo(200);
        assertThat(fakeAi.callCount()).as("A's second, non-forced evaluation is A's own cache hit").isEqualTo(1);

        fakeAi.setNextResult(new AiMatchResult(72, List.of("B skill"), List.of(), List.of()));
        HttpResponse<String> bEvaluated = b.postJson("/api/jobs/" + bJob + "/match/evaluate", "{}");
        assertThat(bEvaluated.statusCode()).isEqualTo(200);
        assertThat(fakeAi.callCount()).as("B is never served A's stored match").isEqualTo(2);
        assertThat((Integer) JsonPath.read(bEvaluated.body(), "$.fitScore")).isEqualTo(72);
        assertThat((Integer) JsonPath.read(a.get("/api/jobs/" + aJob + "/match").body(), "$.fitScore")).isEqualTo(90);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", aJob)).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE user_id = ?", userId(b))).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM job_matches m JOIN jobs j ON j.id = m.job_id "
                + "JOIN cvs c ON c.id = m.cv_id WHERE m.user_id <> j.user_id OR m.user_id <> c.user_id")).isZero();
    }

    // ===================== Companies =====================

    @Test
    void companyRowsAndTheirDataArePerUser() throws Exception {
        // A restores a company with website and notes (the only way to set them).
        TestApiClient restored = login("isolation-company-" + run + "@jobhunter.local");
        ObjectNode job = minimalJob("c1", TITLE);
        job.putObject("company").put("name", COMPANY).put("website", "https://example.com").put("notes", "Restorer's company notes");
        assertThat(restored.postJson("/api/job-data/import", file(job)).statusCode()).isEqualTo(201);

        long bJob = id(b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, null)), "$.jobId");
        assertThat(b.get("/api/job-data/export").body()).doesNotContain("Restorer's company notes").doesNotContain("https://example.com");
        assertThat(testData.count("SELECT count(*) FROM companies WHERE name_key = 'example gmbh' AND user_id IN (?, ?, ?)",
                userId(a), userId(b), userId(restored))).isEqualTo(3);

        // B renaming the company on their job creates/uses B's company only.
        String before = snapshotOfA();
        assertThat(b.patchJson("/api/jobs/" + bJob, jobBody(TITLE, "Example AG", null)).statusCode()).isEqualTo(200);
        assertThat(snapshotOfA()).isEqualTo(before);
        assertThat(restored.get("/api/job-data/export").body()).contains("Restorer's company notes");
        assertThat(JsonPath.<String>read(a.get("/api/jobs/" + aJob).body(), "$.companyName")).isEqualTo(COMPANY);
        // B's unused company row was removed; nobody else's.
        assertThat(testData.count("SELECT count(*) FROM companies WHERE user_id = ?", userId(b))).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE user_id = ?", userId(a))).isEqualTo(1);
    }

    // ===================== Export / import =====================

    @Test
    void eachExportContainsOnlyItsOwnersData() throws Exception {
        b.postJson("/api/board/jobs", jobBody(TITLE, COMPANY, "B ONLY DESCRIPTION"));
        b.postJson("/api/board/jobs", jobBody("B Only Role", "B Only Co", null));

        String aExport = a.get("/api/job-data/export").body();
        String bExport = b.get("/api/job-data/export").body();

        assertThat(JsonPath.<List<String>>read(aExport, "$.jobs[*].title")).containsExactly(TITLE);
        assertThat(aExport).doesNotContain("B ONLY DESCRIPTION").doesNotContain("B Only");
        assertThat(JsonPath.<List<String>>read(bExport, "$.jobs[*].title")).containsExactlyInAnyOrder(TITLE, "B Only Role");
        assertThat(bExport).doesNotContain(A_DESCRIPTION).doesNotContain("A private notes").doesNotContain("A Interview")
                .doesNotContain("A history note");
    }

    @Test
    void aFailedImportRollsBackTheImportersDataOnlyAndNeverTouchesOthers() throws Exception {
        TestApiClient importer = login("isolation-importer-" + run + "@jobhunter.local");
        String before = snapshotOfA();
        ObjectNode poisoned = minimalJob("p", "Poisoned " + run);
        ((tools.jackson.databind.node.ArrayNode) poisoned.get("application").get("stages")).addObject()
                .put("name", "Interview").put("notes", "bad \u0000 byte")
                .put("createdAt", "2025-03-01T10:00:00Z").put("updatedAt", "2025-03-01T10:00:00Z");
        ObjectNode same = minimalJob("s", TITLE);
        same.putObject("company").put("name", COMPANY).putNull("website").putNull("notes");

        HttpResponse<String> response = importer.postJson("/api/job-data/import", file(same, poisoned));

        assertThat(response.statusCode()).isBetween(400, 499);
        assertThat(JsonPath.<List<Object>>read(importer.get("/api/board").body(), "$[*]")).isEmpty();
        assertThat(testData.count("SELECT count(*) FROM companies WHERE user_id = ?", userId(importer))).isZero();
        assertThat(snapshotOfA()).isEqualTo(before);

        // A's existing identical job does not block the importer's valid restore.
        HttpResponse<String> valid = importer.postJson("/api/job-data/import", file(same));
        assertThat(valid.statusCode()).as(valid.body()).isEqualTo(201);
        assertThat(snapshotOfA()).isEqualTo(before);
        // ...and A, who has data, still cannot import (not even into "someone else's" domain).
        assertThat(a.postJson("/api/job-data/import", file(minimalJob("x", "Another " + run))).statusCode()).isEqualTo(409);
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== MCP =====================

    @Test
    void mcpTokensActOnlyForTheirOwnUser() throws Exception {
        String aToken = JsonPath.read(a.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        String bToken = JsonPath.read(b.postJson("/api/settings/integrations/mcp-token", "{}").body(), "$.token");
        String before = snapshotOfA();

        // B's token adding the very same job creates B's own ticket, with B's imported fit.
        HttpResponse<String> bAdd = mcp(bToken, "{\"title\":\"" + TITLE + "\",\"companyName\":\"" + COMPANY
                + "\",\"fitScore\":40,\"userId\":" + userId(a) + ",\"applicationId\":" + aApplication + "}");
        assertThat(bAdd.statusCode()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(bAdd.body(), "$.created")).isTrue();
        assertThat(id(bAdd, "$.jobId")).isNotEqualTo(aJob);
        assertThat(snapshotOfA()).isEqualTo(before);
        assertThat(a.get("/api/jobs/" + aJob + "/match").statusCode()).isEqualTo(404);

        // A's token on A's existing job is "already exists" for A; nothing changes.
        HttpResponse<String> aAdd = mcp(aToken, "{\"title\":\"" + TITLE + "\",\"companyName\":\"" + COMPANY + "\",\"fitScore\":99}");
        assertThat(aAdd.statusCode()).isEqualTo(200);
        assertThat(id(aAdd, "$.applicationId")).isEqualTo(aApplication);

        // A bearer token reaches nothing but the integration endpoint.
        HttpClient http = HttpClient.newHttpClient();
        for (String path : List.of("/api/board", "/api/jobs/" + aJob, "/api/applications/" + aApplication,
                "/api/applications/" + aApplication + "/stages", "/api/job-data/export", "/api/cv", "/api/settings")) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + path))
                    .header("Authorization", "Bearer " + bToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(response.body()).doesNotContain(TITLE);
        }
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== CV / settings =====================

    @Test
    void cvAndSettingsArePerUser() throws Exception {
        configureAi(a, A_CV_TEXT);
        String aSettings = a.get("/api/settings").body();

        HttpResponse<String> bCv = b.get("/api/cv");
        assertThat(bCv.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(bCv.body(), "$.cv")).isNull();
        assertThat(bCv.body()).doesNotContain("Alice Private CV");
        assertThat(b.getBytes("/api/cv/file").statusCode()).isEqualTo(404);

        assertThat(b.putJson("/api/settings", "{\"theme\":\"LIGHT\",\"timezone\":\"Asia/Tokyo\",\"visibleBoardStatuses\":[\"OFFER\"],"
                + "\"minimumFitScore\":10,\"aiProvider\":\"GEMINI\",\"aiModel\":\"b-model\"}").statusCode()).isEqualTo(200);
        assertThat(b.putJson("/api/settings/ai/api-key", "{\"provider\":\"GEMINI\",\"apiKey\":\"b-key\"}").statusCode()).isEqualTo(200);
        assertThat(b.deleteRequest("/api/settings/ai").statusCode()).isEqualTo(204);
        assertThat(b.postMultipartFile("/api/cv", "file", "b.pdf", "application/pdf",
                TestFiles.pdfWithText("Bob replacement CV with enough text to extract properly.")).statusCode()).isEqualTo(201);

        assertThat(a.get("/api/settings").body()).isEqualTo(aSettings);
        assertThat(JsonPath.<String>read(a.get("/api/cv").body(), "$.cv.extractedText")).contains("Alice Private CV");
        assertThat(new String(a.getBytes("/api/cv/file").body())).startsWith("%PDF");
        assertThat(JsonPath.<String>read(b.get("/api/cv").body(), "$.cv.extractedText")).doesNotContain("Alice");
    }

    // ===================== Guessed ids sweep =====================

    @Test
    void guessedIdsOfEveryKindAreNotFoundForAnotherUser() throws Exception {
        long bApplication = id(b.postJson("/api/board/jobs", jobBody("B Sweep", COMPANY, null)), "$.id");
        long bJob = JsonPath.<Number>read(b.get(app(bApplication)).body(), "$.jobId").longValue();
        String before = snapshotOfA();
        String stageBody = "{\"name\":\"X\"}";

        List<HttpResponse<String>> responses = List.of(
                b.get("/api/jobs/" + aJob),
                b.get("/api/jobs/" + aJob + "/match"),
                b.postJson("/api/jobs/" + aJob + "/match/evaluate", "{}"),
                b.patchJson("/api/jobs/" + aJob, jobBody("X", "Y", null)),
                b.get(app(aApplication)),
                b.get(app(aApplication) + "/stages"),
                b.get(app(aApplication) + "/status-history"),
                b.postJson(app(aApplication) + "/stages", stageBody),
                b.patchJson(app(aApplication) + "/stages/" + aStage, stageBody),
                b.deleteRequest(app(aApplication) + "/stages/" + aStage),
                b.patchJson(app(bApplication) + "/stages/" + aStage, stageBody),
                b.deleteRequest(app(bApplication) + "/stages/" + aStage),
                b.patchJson(app(aApplication) + "/status", "{\"statusCode\":\"OFFER\"}"),
                b.patchJson(app(aApplication) + "/move", "{\"statusCode\":\"OFFER\"}"),
                b.patchJson(app(aApplication) + "/starred", "{\"starred\":true}"),
                b.patchJson(app(aApplication) + "/notes", "{\"notes\":\"X\"}"),
                b.deleteRequest(app(aApplication)));
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri()).isEqualTo(404);
            assertThat(response.body()).doesNotContain(TITLE).doesNotContain("A private").doesNotContain("A Interview");
        }
        // Sanity: B's own ids work for B.
        assertThat(b.get("/api/jobs/" + bJob).statusCode()).isEqualTo(200);
        assertThat(snapshotOfA()).isEqualTo(before);
    }

    // ===================== Helpers =====================

    /** Everything of A's job domain the API can show, as one comparable string. */
    private String snapshotOfA() throws Exception {
        ObjectNode export = (ObjectNode) JSON.readTree(a.get("/api/job-data/export").body());
        export.remove("exportedAt");
        HttpResponse<String> match = a.get("/api/jobs/" + aJob + "/match");
        return export + "\n" + a.get("/api/board").body() + "\n" + a.get("/api/jobs/" + aJob).body()
                + "\n" + a.get(app(aApplication) + "/stages").body() + "\n" + a.get(app(aApplication) + "/status-history").body()
                // A 404 body carries a timestamp; only "no match" matters then.
                + "\n" + (match.statusCode() == 200 ? match.body() : "no match: " + match.statusCode());
    }

    private TestApiClient login(String email) throws Exception {
        testData.createUserIfMissing(email, PASSWORD);
        TestApiClient api = new TestApiClient(port);
        assertThat(api.login(email, PASSWORD).statusCode()).isEqualTo(200);
        return api;
    }

    private long userId(TestApiClient api) throws Exception {
        return JsonPath.<Number>read(api.get("/api/auth/me").body(), "$.id").longValue();
    }

    private void configureAi(TestApiClient api, String cvText) throws Exception {
        assertThat(api.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf", TestFiles.pdfWithText(cvText))
                .statusCode()).isEqualTo(201);
        assertThat(api.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":[\"NEW\"],"
                + "\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(api.putJson("/api/settings/ai/api-key", "{\"provider\":\"ANTHROPIC\",\"apiKey\":\""
                + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> mcp(String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/integrations/jobs"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<Long> order(TestApiClient api) throws Exception {
        List<Number> ids = JsonPath.read(api.get("/api/board").body(), "$[?(@.statusCode == 'NEW')].applicationId");
        return ids.stream().map(Number::longValue).toList();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String app(long applicationId) {
        return "/api/applications/" + applicationId;
    }

    private static long id(HttpResponse<String> response, String path) {
        return JsonPath.<Number>read(response.body(), path).longValue();
    }

    private static String jobBody(String title, String company, String description) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\""
                + (description != null ? ",\"description\":\"" + description + "\"" : "") + "}";
    }

    private static ObjectNode minimalJob(String ref, String title) {
        ObjectNode job = JSON.createObjectNode();
        job.put("ref", ref).put("title", title).putNull("company").putNull("description").put("descriptionDiscarded", false)
                .putNull("location").putNull("workModel").putNull("url").putNull("salaryMin").putNull("salaryMax")
                .putNull("currency").putNull("source").putNull("externalId")
                .put("discoveredAt", "2025-01-10T08:00:00Z").put("createdAt", "2025-01-10T08:00:00Z")
                .put("updatedAt", "2025-01-12T08:00:00Z");
        ObjectNode application = job.putObject("application");
        application.put("status", "NEW").put("boardPosition", 0).put("starred", false).putNull("notes").putNull("appliedAt")
                .put("createdAt", "2025-01-15T10:00:00Z").put("updatedAt", "2025-01-15T10:00:00Z");
        application.putArray("statusHistory");
        application.putArray("stages");
        job.putNull("aiMatch");
        return job;
    }

    private static String file(ObjectNode... jobs) {
        ObjectNode file = JSON.createObjectNode();
        file.put("format", "job-hunter-jobs").put("formatVersion", 1).put("exportedAt", "2025-06-01T00:00:00Z");
        var array = file.putArray("jobs");
        for (ObjectNode job : jobs) {
            array.add(job);
        }
        return file.toString();
    }
}
