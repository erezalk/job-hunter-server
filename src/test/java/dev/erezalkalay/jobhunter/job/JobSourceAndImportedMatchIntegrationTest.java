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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Job.source (free-text metadata, "Manual" / "External AI" defaults) and the
 * optional already-calculated fit result accepted by the MCP integration.
 */
class JobSourceAndImportedMatchIntegrationTest extends IntegrationTestBase {

    private static final String OWNER_EMAIL = "test@jobhunter.local";
    private static final String TOKEN_URL = "/api/settings/integrations/mcp-token";
    private static final int THRESHOLD = 70;

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testDataSupport;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    private final HttpClient bare = HttpClient.newHttpClient();
    private TestApiClient browser;
    private String token;
    private String company;

    @BeforeEach
    void setUp() throws Exception {
        browser = new TestApiClient(port);
        assertThat(browser.login(OWNER_EMAIL, "test-password-123").statusCode()).isEqualTo(200);
        company = "Source Co " + System.nanoTime();
        fakeAiProviderState.reset();
        token = JsonPath.read(browser.postJson(TOKEN_URL, "{}").body(), "$.token");
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> mcpAdd(String json) throws Exception {
        return mcpAdd(token, json);
    }

    private HttpResponse<String> mcpAdd(String bearer, String json) throws Exception {
        return bare.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/integrations/jobs"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .POST(BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    private String job(String title, String extra) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private long jobIdOf(HttpResponse<String> added) {
        return JsonPath.<Number>read(added.body(), "$.jobId").longValue();
    }

    private String jobField(long jobId, String path) throws Exception {
        return JsonPath.read(browser.get("/api/jobs/" + jobId).body(), path);
    }

    private HttpResponse<String> match(long jobId) throws Exception {
        return browser.get("/api/jobs/" + jobId + "/match");
    }

    private List<String> boardTitles() throws Exception {
        return JsonPath.read(browser.get("/api/board").body(), "$[?(@.companyName=='" + company + "')].title");
    }

    private String editBody(String title, String sourceJson, String extra) {
        return "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\",\"description\":\"d\",\"source\":" + sourceJson
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** CV + AI settings so that an explicit (re-)evaluation can run against the fake provider. */
    private void prepareAi() throws Exception {
        User user = userRepository.findByEmailIgnoreCase(OWNER_EMAIL).orElseThrow();
        testDataSupport.clearActiveCv(user.getId());
        assertThat(browser.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Erez Alkalay - Senior Java Engineer with 10 years of Spring Boot.")).statusCode())
                .isEqualTo(201);
        List<String> visible = JsonPath.read(browser.get("/api/settings").body(), "$.visibleBoardStatuses");
        String visibleJson = "[" + String.join(",", visible.stream().map(v -> "\"" + v + "\"").toList()) + "]";
        assertThat(browser.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + visibleJson + ",\"minimumFitScore\":" + THRESHOLD
                + ",\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}").statusCode()).isEqualTo(200);
        assertThat(browser.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}")
                .statusCode()).isEqualTo(200);
    }

    private long addWithFit(String title, String fitJson) throws Exception {
        HttpResponse<String> added = mcpAdd(job(title, fitJson));
        assertThat(added.statusCode()).isEqualTo(201);
        return jobIdOf(added);
    }

    private static final String FIT = "\"fitScore\":92,\"matchingSkills\":[\"Java\",\"Spring Boot\"],"
            + "\"missingSkills\":[\"Kubernetes\"],\"concerns\":[\"On-call rotation\"],\"evaluationModel\":\"Claude Opus 5.5\"";

    // ----------------------------------------------------------------- source

    @Test
    void jobsWithoutAKnownSourceHaveNullSource() throws Exception {
        testDataSupport.insertRawJob(testDataSupport.userId("test@jobhunter.local"),
                "Unknown Source Role", "unknown-source-co", "unknown-source-role-" + System.nanoTime());
        assertThat(testDataSupport.count("select count(*) from jobs where title = 'Unknown Source Role' and source is null"))
                .isEqualTo(1);
        // There is no column default: a job without a recorded source is "unknown", never a placeholder.
        assertThat(testDataSupport.count("select count(*) from jobs where source = 'MANUAL'")).isZero();
    }

    @Test
    void browserAddJobAssignsManualServerSideAndIgnoresAnySubmittedSource() throws Exception {
        HttpResponse<String> plain = browser.postJson("/api/board/jobs", job("Browser Role", ""));
        HttpResponse<String> sneaky = browser.postJson("/api/board/jobs", job("Browser Role 2", "\"source\":\"LinkedIn\""));
        assertThat(plain.statusCode()).isEqualTo(201);
        assertThat(sneaky.statusCode()).isEqualTo(201);

        assertThat(jobField(JsonPath.<Number>read(plain.body(), "$.jobId").longValue(), "$.source")).isEqualTo("Manual");
        assertThat(jobField(JsonPath.<Number>read(sneaky.body(), "$.jobId").longValue(), "$.source")).isEqualTo("Manual");
    }

    @Test
    void mcpAddJobAssignsExternalAiAndTheBodyCannotChooseTheSource() throws Exception {
        HttpResponse<String> plain = mcpAdd(job("Mcp Role", ""));
        HttpResponse<String> sneaky = mcpAdd(job("Mcp Role 2", "\"source\":\"Manual\""));

        assertThat(plain.statusCode()).isEqualTo(201);
        assertThat(sneaky.statusCode()).isEqualTo(201);
        assertThat(jobField(jobIdOf(plain), "$.source")).isEqualTo("External AI");
        assertThat(jobField(jobIdOf(sneaky), "$.source")).isEqualTo("External AI");
    }

    @Test
    void sourceIsEditableFreeTextTrimmedAndBlankBecomesNull() throws Exception {
        long id = jobIdOf(mcpAdd(job("Editable Source", "")));

        for (String free : new String[] {"LinkedIn", "Company website", "Referral from Dana", "Claude search"}) {
            HttpResponse<String> edited = browser.patchJson("/api/jobs/" + id, editBody("Editable Source", "\"" + free + "\"", ""));
            assertThat(edited.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(edited.body(), "$.source")).isEqualTo(free);
            assertThat(jobField(id, "$.source")).isEqualTo(free);
        }
        HttpResponse<String> trimmed = browser.patchJson("/api/jobs/" + id, editBody("Editable Source", "\"  Indeed  \"", ""));
        assertThat(JsonPath.<String>read(trimmed.body(), "$.source")).isEqualTo("Indeed");

        for (String blank : new String[] {"\"\"", "\"   \"", "null"}) {
            browser.patchJson("/api/jobs/" + id, editBody("Editable Source", "\"Indeed\"", ""));
            HttpResponse<String> cleared = browser.patchJson("/api/jobs/" + id, editBody("Editable Source", blank, ""));
            assertThat(cleared.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Object>read(cleared.body(), "$.source")).as(blank).isNull();
        }
    }

    @Test
    void overlongSourceIsRejected() throws Exception {
        long id = jobIdOf(mcpAdd(job("Long Source", "")));

        HttpResponse<String> tooLong = browser.patchJson("/api/jobs/" + id,
                editBody("Long Source", "\"" + "x".repeat(101) + "\"", ""));
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(tooLong.body(), "$.fieldErrors[*].field")).contains("source");
        assertThat(jobField(id, "$.source")).isEqualTo("External AI");
    }

    @Test
    void editingSourceKeepsTheMatchAndTheStatusAndNeverCallsTheProvider() throws Exception {
        long id = addWithFit("Source Only Edit", FIT);
        long applicationId = JsonPath.<List<Number>>read(browser.get("/api/board").body(),
                "$[?(@.jobId==" + id + ")].applicationId").get(0).longValue();

        HttpResponse<String> edited = browser.patchJson("/api/jobs/" + id, "{\"title\":\"Source Only Edit\",\"companyName\":\""
                + company + "\",\"source\":\"Recruiter\"}");
        // The same edit with only the source changed (description absent both times): AI input unchanged.
        assertThat(edited.statusCode()).isEqualTo(200);

        assertThat(match(id).statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(match(id).body(), "$.fitScore")).isEqualTo(92);
        assertThat(JsonPath.<List<String>>read(browser.get("/api/applications/" + applicationId + "/status-history").body(),
                "$[*].status.code")).containsExactly("NEW");
        assertThat(fakeAiProviderState.callCount()).isZero();
    }

    @Test
    void sourceTakesNoPartInDeduplication() throws Exception {
        HttpResponse<String> manual = browser.postJson("/api/board/jobs", job("Dedup Role", ""));
        assertThat(manual.statusCode()).isEqualTo(201);
        long id = JsonPath.<Number>read(manual.body(), "$.jobId").longValue();
        browser.patchJson("/api/jobs/" + id, editBody("Dedup Role", "\"Totally Different\"", ""));

        HttpResponse<String> viaMcp = mcpAdd(job("dedup role", ""));
        assertThat(viaMcp.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(viaMcp.body(), "$.created")).isFalse();
        assertThat(jobIdOf(viaMcp)).isEqualTo(id);
        assertThat(jobField(id, "$.source")).isEqualTo("Totally Different");
        assertThat(boardTitles()).hasSize(1);
    }

    // --------------------------------------------------------- imported match

    @Test
    void addJobWithoutFitDataCreatesNoMatchAndNeverCallsTheProvider() throws Exception {
        prepareAi();
        HttpResponse<String> added = mcpAdd(job("No Fit", ""));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(added.body(), "$.importedFitScore")).isNull();
        assertThat(JsonPath.<String>read(added.body(), "$.statusCode")).isEqualTo("NEW");
        assertThat(match(jobIdOf(added)).statusCode()).isEqualTo(404);
        assertThat(fakeAiProviderState.callCount()).isZero();
        // Empty/blank evaluation fields count as "not supplied".
        HttpResponse<String> empties = mcpAdd(job("Empty Fit", "\"matchingSkills\":[],\"missingSkills\":[\" \"],\"evaluationModel\":\"\""));
        assertThat(empties.statusCode()).isEqualTo(201);
        assertThat(match(jobIdOf(empties)).statusCode()).isEqualTo(404);
    }

    @Test
    void addJobWithFitDataStoresAnExternalAiJobMatchWithoutCallingTheProvider() throws Exception {
        prepareAi();
        HttpResponse<String> added = mcpAdd(job("With Fit", FIT));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(added.body(), "$.importedFitScore")).isEqualTo(92);
        long id = jobIdOf(added);

        HttpResponse<String> match = match(id);
        assertThat(match.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(match.body(), "$.fitScore")).isEqualTo(92);
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).containsExactly("Java", "Spring Boot");
        assertThat(JsonPath.<List<String>>read(match.body(), "$.missingSkills")).containsExactly("Kubernetes");
        assertThat(JsonPath.<List<String>>read(match.body(), "$.concerns")).containsExactly("On-call rotation");
        assertThat(JsonPath.<String>read(match.body(), "$.provider")).isEqualTo("EXTERNAL_AI");
        assertThat(JsonPath.<String>read(match.body(), "$.model")).isEqualTo("Claude Opus 5.5");
        assertThat(JsonPath.<Object>read(match.body(), "$.evaluatedAt")).isNotNull();
        assertThat(fakeAiProviderState.callCount()).isZero();

        // Same single JobMatch table, same fingerprint/CV metadata as an internal evaluation, owned by the token's user.
        assertThat(testDataSupport.count("""
                select count(*) from job_matches m join users u on u.id = m.user_id
                where m.job_id = ? and u.email = ? and m.fingerprint is not null and m.cv_id is not null
                  and m.provider = 'EXTERNAL_AI'""", id, OWNER_EMAIL)).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(browser.get("/api/board").body(), "$[?(@.jobId==" + id + ")].fitScore").get(0))
                .isNotNull();
    }

    @Test
    void evaluationModelIsOptional() throws Exception {
        long id = addWithFit("No Model", "\"fitScore\":55");

        HttpResponse<String> match = match(id);
        assertThat(JsonPath.<Integer>read(match.body(), "$.fitScore")).isEqualTo(55);
        assertThat(JsonPath.<String>read(match.body(), "$.provider")).isEqualTo("EXTERNAL_AI");
        assertThat(JsonPath.<Object>read(match.body(), "$.model")).isNull();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).isEmpty();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.missingSkills")).isEmpty();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.concerns")).isEmpty();
    }

    @Test
    void fitListsAreTrimmedAndBlankAndDuplicateEntriesDropped() throws Exception {
        long id = addWithFit("Normalized", "\"fitScore\":80,\"matchingSkills\":[\"  Java  \",\"\",\"java\",\"5+ years of Java (10+ years total)\"],"
                + "\"missingSkills\":[\"   \"],\"concerns\":[\"Explicit mention of Kubernetes\"]");

        HttpResponse<String> match = match(id);
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills"))
                .containsExactly("Java", "5+ years of Java (10+ years total)");
        assertThat(JsonPath.<List<String>>read(match.body(), "$.missingSkills")).isEmpty();
        assertThat(JsonPath.<List<String>>read(match.body(), "$.concerns")).containsExactly("Explicit mention of Kubernetes");
    }

    @Test
    void invalidFitScoreIsRejectedAndNothingIsCreated() throws Exception {
        for (String score : new String[] {"101", "-1", "1000", "\"high\"", "92.5"}) {
            assertThat(mcpAdd(job("Bad Score", "\"fitScore\":" + score)).statusCode()).as(score).isEqualTo(400);
        }
        assertThat(mcpAdd(job("Boundary Zero", "\"fitScore\":0")).statusCode()).isEqualTo(201);
        assertThat(mcpAdd(job("Boundary Hundred", "\"fitScore\":100")).statusCode()).isEqualTo(201);
        assertThat(boardTitles()).containsExactlyInAnyOrder("Boundary Zero", "Boundary Hundred");
    }

    @Test
    void evaluationDetailsWithoutAFitScoreAreRejectedAndNothingIsCreated() throws Exception {
        for (String extra : new String[] {"\"matchingSkills\":[\"Java\"]", "\"missingSkills\":[\"Go\"]",
                "\"concerns\":[\"Travel\"]", "\"evaluationModel\":\"Claude Opus 5.5\""}) {
            HttpResponse<String> rejected = mcpAdd(job("Partial Fit", extra));
            assertThat(rejected.statusCode()).as(extra).isEqualTo(400);
            assertThat(JsonPath.<String>read(rejected.body(), "$.message")).contains("fitScore");
        }
        assertThat(boardTitles()).isEmpty();
    }

    @Test
    void abusivePayloadSizesAreRejected() throws Exception {
        String tooMany = String.join(",", java.util.Collections.nCopies(60, "\"skill\""));
        assertThat(mcpAdd(job("Many", "\"fitScore\":50,\"matchingSkills\":[" + tooMany + "]")).statusCode()).isEqualTo(400);
        assertThat(mcpAdd(job("Long", "\"fitScore\":50,\"concerns\":[\"" + "x".repeat(1001) + "\"]")).statusCode()).isEqualTo(400);
        assertThat(mcpAdd(job("Model", "\"fitScore\":50,\"evaluationModel\":\"" + "m".repeat(101) + "\"")).statusCode()).isEqualTo(400);
        assertThat(boardTitles()).isEmpty();
    }

    @Test
    void duplicateJobNeitherCreatesNorOverwritesAMatch() throws Exception {
        long first = addWithFit("Dup With Fit", "\"fitScore\":80,\"matchingSkills\":[\"Java\"]");
        HttpResponse<String> second = mcpAdd(job("dup with fit", "\"fitScore\":10,\"missingSkills\":[\"Everything\"]"));

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(second.body(), "$.created")).isFalse();
        assertThat(JsonPath.<Object>read(second.body(), "$.importedFitScore")).isNull();
        HttpResponse<String> match = match(first);
        assertThat(JsonPath.<Integer>read(match.body(), "$.fitScore")).isEqualTo(80);
        assertThat(JsonPath.<List<String>>read(match.body(), "$.missingSkills")).isEmpty();

        long unevaluated = jobIdOf(mcpAdd(job("Dup Without Fit", "")));
        assertThat(mcpAdd(job("Dup Without Fit", "\"fitScore\":90")).statusCode()).isEqualTo(200);
        assertThat(match(unevaluated).statusCode()).isEqualTo(404);
    }

    @Test
    void importedScoreBelowTheThresholdDoesNotMoveTheJobToLowMatch() throws Exception {
        prepareAi();
        HttpResponse<String> added = mcpAdd(job("Low Imported", "\"fitScore\":20,\"missingSkills\":[\"Most of it\"]"));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(added.body(), "$.statusCode")).isEqualTo("NEW");
        long id = jobIdOf(added);
        long applicationId = JsonPath.<Number>read(added.body(), "$.applicationId").longValue();
        assertThat(JsonPath.<List<String>>read(browser.get("/api/applications/" + applicationId + "/status-history").body(),
                "$[*].status.code")).containsExactly("NEW");
        // Nothing is discarded either: the description stays.
        assertThat((Boolean) JsonPath.read(browser.get("/api/jobs/" + id).body(), "$.descriptionDiscarded")).isFalse();
        assertThat(JsonPath.<Integer>read(match(id).body(), "$.fitScore")).isEqualTo(20);
        assertThat(JsonPath.<List<String>>read(match(id).body(), "$.missingSkills")).containsExactly("Most of it");
    }

    @Test
    void explicitReEvaluateReplacesTheImportedMatchWithTheConfiguredProvidersResult() throws Exception {
        prepareAi();
        long id = addWithFit("Re-evaluated", "\"fitScore\":88,\"matchingSkills\":[\"Imported\"],\"evaluationModel\":\"Claude Opus 5.5\"");
        fakeAiProviderState.setNextResult(new AiMatchResult(91, List.of("Java"), List.of("Kubernetes"), List.of()));

        HttpResponse<String> evaluated = browser.postJson("/api/jobs/" + id + "/match/evaluate?force=true", "");

        assertThat(evaluated.statusCode()).isEqualTo(200);
        assertThat(fakeAiProviderState.callCount()).isEqualTo(1);
        HttpResponse<String> match = match(id);
        assertThat(JsonPath.<Integer>read(match.body(), "$.fitScore")).isEqualTo(91);
        assertThat(JsonPath.<String>read(match.body(), "$.provider")).isEqualTo("ANTHROPIC");
        assertThat(JsonPath.<String>read(match.body(), "$.model")).isEqualTo("test-model");
        assertThat(JsonPath.<List<String>>read(match.body(), "$.matchingSkills")).containsExactly("Java");
        assertThat(testDataSupport.count("select count(*) from job_matches where job_id = ?", id)).isEqualTo(1);
    }

    @Test
    void aReEvaluateBelowTheThresholdAppliesTheNormalLowMatchRuleToAnImportedJob() throws Exception {
        prepareAi();
        long id = addWithFit("Later Low", "\"fitScore\":95");
        fakeAiProviderState.setNextResult(new AiMatchResult(30, List.of(), List.of(), List.of()));

        assertThat(browser.postJson("/api/jobs/" + id + "/match/evaluate?force=true", "").statusCode()).isEqualTo(200);

        assertThat(JsonPath.<List<String>>read(browser.get("/api/board").body(), "$[?(@.jobId==" + id + ")].statusCode"))
                .containsExactly("LOW_MATCH");
    }

    @Test
    void aiRelevantJobEditInvalidatesTheImportedMatchLikeAnyOther() throws Exception {
        long id = addWithFit("Stale Imported", FIT);
        assertThat(match(id).statusCode()).isEqualTo(200);

        HttpResponse<String> edited = browser.patchJson("/api/jobs/" + id,
                editBody("Stale Imported", "\"External AI\"", "\"location\":\"Berlin\""));

        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(match(id).statusCode()).isEqualTo(404);
        assertThat(fakeAiProviderState.callCount()).isZero();
    }

    @Test
    void importedMatchBelongsToTheTokenOwnerEvenIfTheBodyNamesAnotherUser() throws Exception {
        testDataSupport.createUserIfMissing("import-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("import-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);

        HttpResponse<String> added = mcpAdd(job("Owned", FIT + ",\"userId\":999999,\"email\":\"import-other@jobhunter.local\""));

        assertThat(added.statusCode()).isEqualTo(201);
        long id = jobIdOf(added);
        assertThat(match(id).statusCode()).isEqualTo(200);
        assertThat(other.get("/api/jobs/" + id + "/match").statusCode()).isEqualTo(404);
        assertThat(testDataSupport.count("""
                select count(*) from job_matches m join users u on u.id = m.user_id
                where m.job_id = ? and u.email = 'import-other@jobhunter.local'""", id)).isZero();
    }

    @Test
    void browserAddJobStillCreatesNoMatchAndNeverCallsTheProvider() throws Exception {
        prepareAi();
        HttpResponse<String> added = browser.postJson("/api/board/jobs",
                job("Browser No Fit", "\"fitScore\":99,\"matchingSkills\":[\"Java\"]"));

        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(match(JsonPath.<Number>read(added.body(), "$.jobId").longValue()).statusCode()).isEqualTo(404);
        assertThat(fakeAiProviderState.callCount()).isZero();
    }
}
