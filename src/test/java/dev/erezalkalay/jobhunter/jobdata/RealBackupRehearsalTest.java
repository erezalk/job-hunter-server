package dev.erezalkalay.jobhunter.jobdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Restore rehearsal for a REAL v1 backup, run only on demand against the
 * disposable Testcontainers database:
 *
 * <pre>./mvnw test -Dtest=RealBackupRehearsalTest -Djobhunter.rehearsal.file=/path/to/job-hunter-export.json
 *     [-Djobhunter.rehearsal.expectedJobs=90] [-Djobhunter.rehearsal.minFutureStages=3]</pre>
 *
 * The file is only read (never copied into the project); the test prints
 * aggregate counts only, never contents. Another user first tracks the same
 * company + title as the backup's jobs, to prove that does not block the
 * restore and is left untouched. The re-export must equal the backup apart from
 * {@code exportedAt}.
 */
@EnabledIfSystemProperty(named = "jobhunter.rehearsal.file", matches = ".+")
class RealBackupRehearsalTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "rehearsal-password-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Test
    void theRealBackupRestoresIntoTheNewSchemaAndReExportsIdentically() throws Exception {
        Path file = Path.of(System.getProperty("jobhunter.rehearsal.file"));
        String backup = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode original = JSON.readTree(backup);
        assertThat(original.get("format").asString()).isEqualTo("job-hunter-jobs");
        assertThat(original.get("formatVersion").asInt()).isEqualTo(1);
        int jobs = original.get("jobs").size();
        String expectedJobs = System.getProperty("jobhunter.rehearsal.expectedJobs");
        if (expectedJobs != null) {
            assertThat(jobs).isEqualTo(Integer.parseInt(expectedJobs));
        }

        String stamp = String.valueOf(System.nanoTime());
        TestApiClient owner = login("rehearsal-owner-" + stamp + "@jobhunter.local");
        TestApiClient other = login("rehearsal-other-" + stamp + "@jobhunter.local");
        // The other user already tracks the backup's first jobs (same company + title) with different data.
        for (int i = 0; i < Math.min(5, jobs); i++) {
            JsonNode job = original.get("jobs").get(i);
            ObjectNode body = JSON.createObjectNode().put("title", job.get("title").asString()).put("description", "OTHER USER");
            if (!job.get("company").isNull()) {
                body.put("companyName", job.get("company").get("name").asString());
            }
            assertThat(other.postJson("/api/board/jobs", body.toString()).statusCode()).isEqualTo(201);
        }
        String otherBefore = withoutExportedAt(other.get("/api/job-data/export").body()).toString();

        HttpResponse<String> imported = owner.postJson("/api/job-data/import", backup);
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);

        // Counts against the backup's own content.
        int stages = JsonPath.<List<Object>>read(backup, "$.jobs[*].application.stages[*]").size();
        int history = JsonPath.<List<Object>>read(backup, "$.jobs[*].application.statusHistory[*]").size();
        int matches = JsonPath.<List<Object>>read(backup, "$.jobs[?(@.aiMatch != null)]").size();
        assertThat((Integer) JsonPath.read(imported.body(), "$.jobs")).isEqualTo(jobs);
        assertThat((Integer) JsonPath.read(imported.body(), "$.stages")).isEqualTo(stages);
        assertThat((Integer) JsonPath.read(imported.body(), "$.statusHistoryEntries")).isEqualTo(history);
        assertThat((Integer) JsonPath.read(imported.body(), "$.aiMatches")).isEqualTo(matches);

        String board = owner.get("/api/board").body();
        assertThat(JsonPath.<List<Object>>read(board, "$[*]")).hasSize(jobs);
        Map<String, Long> expectedStatuses = countBy(JsonPath.read(backup, "$.jobs[*].application.status"));
        assertThat(countBy(JsonPath.read(board, "$[*].statusCode"))).isEqualTo(expectedStatuses);
        assertThat(JsonPath.<List<Object>>read(board, "$[?(@.starred == true)]"))
                .hasSize(JsonPath.<List<Object>>read(backup, "$.jobs[?(@.application.starred == true)]").size());

        Instant exportedAt = Instant.parse(original.get("exportedAt").asString());
        long futureAtExport = JsonPath.<List<String>>read(backup, "$.jobs[*].application.stages[*].scheduledAt").stream()
                .filter(at -> at != null && Instant.parse(at).isAfter(exportedAt)).count();
        String minFuture = System.getProperty("jobhunter.rehearsal.minFutureStages");
        if (minFuture != null) {
            assertThat(futureAtExport).isGreaterThanOrEqualTo(Long.parseLong(minFuture));
        }
        long userId = testData.userId("rehearsal-owner-" + stamp + "@jobhunter.local");
        assertThat(testData.count("SELECT count(*) FROM application_stages s JOIN applications a ON a.id = s.application_id "
                + "WHERE a.user_id = ? AND s.scheduled_at > ?", userId, exportedAt.atOffset(java.time.ZoneOffset.UTC)))
                .isEqualTo((int) futureAtExport);
        int futureNow = JsonPath.<List<Object>>read(owner.get("/api/stages/upcoming").body(), "$[*]").size();
        // Restored matches are history only: no CV link, no reuse fingerprint.
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE user_id = ? AND (fingerprint IS NOT NULL OR cv_id IS NOT NULL)",
                userId)).isZero();

        // Re-export: the same logical data, apart from exportedAt.
        String reExport = owner.get("/api/job-data/export").body();
        JsonNode reExported = withoutExportedAt(reExport);
        JsonNode expected = withoutExportedAt(backup);
        assertThat(reExported).isEqualTo(expected);

        // The other user's identical-looking jobs are untouched; nothing of theirs leaked into the owner's export.
        assertThat(withoutExportedAt(other.get("/api/job-data/export").body()).toString()).isEqualTo(otherBefore);
        assertThat(reExport).doesNotContain("OTHER USER");

        System.out.printf("%nREHEARSAL SUMMARY: jobs=%d stages=%d history=%d aiMatches=%d statuses=%s starred=%d "
                        + "futureStagesAtExport=%d upcomingNow=%d companies=%d reExportIdentical=%s%n%n",
                jobs, stages, history, matches, new TreeMap<>(expectedStatuses),
                JsonPath.<List<Object>>read(board, "$[?(@.starred == true)]").size(), futureAtExport, futureNow,
                testData.count("SELECT count(*) FROM companies WHERE user_id = ?", userId), reExported.equals(expected));
    }

    private TestApiClient login(String email) throws Exception {
        testData.createUserIfMissing(email, PASSWORD);
        TestApiClient api = new TestApiClient(port);
        assertThat(api.login(email, PASSWORD).statusCode()).isEqualTo(200);
        return api;
    }

    private static Map<String, Long> countBy(List<String> values) {
        Map<String, Long> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1L, Long::sum));
        return counts;
    }

    private static JsonNode withoutExportedAt(String export) {
        ObjectNode node = (ObjectNode) JSON.readTree(export);
        node.remove("exportedAt");
        return node;
    }
}
