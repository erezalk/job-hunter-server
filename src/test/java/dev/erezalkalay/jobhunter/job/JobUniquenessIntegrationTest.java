package dev.erezalkalay.jobhunter.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;

class JobUniquenessIntegrationTest extends IntegrationTestBase {

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
    void exactDuplicateReusesExistingJobAndApplication() throws Exception {
        HttpResponse<String> first = addJob("Verify Corp", "Senior Backend Engineer");
        HttpResponse<String> second = addJob("Verify Corp", "Senior Backend Engineer");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(jobId(second)).isEqualTo(jobId(first));
        assertThat(applicationId(second)).isEqualTo(applicationId(first));
        assertThat(boardCardsForJob(jobId(first))).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM applications WHERE job_id = ?", jobId(first))).isEqualTo(1);
    }

    @Test
    void caseAndWhitespaceDifferencesAreTheSameJob() throws Exception {
        HttpResponse<String> original = addJob("Case Corp", "Data Engineer");

        for (String[] variant : new String[][] {
                {"case corp", "Data Engineer"},
                {"CASE CORP", "data engineer"},
                {"  Case Corp  ", "  Data Engineer\t"},
                {"Case    Corp", "Data   \n Engineer"}}) {
            HttpResponse<String> duplicate = addJob(variant[0], variant[1]);
            assertThat(duplicate.statusCode()).as(variant[0] + " / " + variant[1]).isEqualTo(200);
            assertThat(jobId(duplicate)).isEqualTo(jobId(original));
        }
    }

    @Test
    void differentCompanyOrDifferentTitleIsAllowed() throws Exception {
        HttpResponse<String> original = addJob("Base Corp", "Platform Engineer");
        HttpResponse<String> otherCompany = addJob("Other Base Corp", "Platform Engineer");
        HttpResponse<String> otherTitle = addJob("Base Corp", "Platform Engineer II");
        // No fuzzy matching: a longer legal name is a different company.
        HttpResponse<String> longerName = addJob("Base Corp GmbH", "Platform Engineer");

        assertThat(otherCompany.statusCode()).isEqualTo(201);
        assertThat(otherTitle.statusCode()).isEqualTo(201);
        assertThat(longerName.statusCode()).isEqualTo(201);
        assertThat(List.of(jobId(original), jobId(otherCompany), jobId(otherTitle), jobId(longerName))).doesNotHaveDuplicates();
    }

    @Test
    void sameCompanyAndTitleInDifferentLocationIsStillADuplicate() throws Exception {
        HttpResponse<String> first = client.postJson("/api/board/jobs",
                "{\"title\":\"Location Role\",\"companyName\":\"Location Co\",\"location\":\"Berlin\"}");
        HttpResponse<String> second = client.postJson("/api/board/jobs",
                "{\"title\":\"Location Role\",\"companyName\":\"Location Co\",\"location\":\"Munich\"}");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(jobId(second)).isEqualTo(jobId(first));
    }

    @Test
    void anotherUserCanTrackTheSameCompanyAndTitleIndependently() throws Exception {
        testData.createUserIfMissing("uniqueness-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("uniqueness-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);
        HttpResponse<String> mine = addJob("Per User Corp", "Per User Engineer");

        HttpResponse<String> theirs = other.postJson("/api/board/jobs",
                "{\"title\":\"per user engineer\",\"companyName\":\"PER USER CORP\"}");

        // A new, separate ticket for them (201), not my existing one (which would be 200 "already on the Board").
        assertThat(theirs.statusCode()).isEqualTo(201);
        assertThat(jobId(theirs)).isNotEqualTo(jobId(mine));
        assertThat(applicationId(theirs)).isNotEqualTo(applicationId(mine));
        assertThat(boardCardsForJob(jobId(theirs))).isZero();
        // Their own duplicate rule still applies to them.
        HttpResponse<String> theirsAgain = other.postJson("/api/board/jobs",
                "{\"title\":\"Per User Engineer\",\"companyName\":\"Per User Corp\"}");
        assertThat(theirsAgain.statusCode()).isEqualTo(200);
        assertThat(jobId(theirsAgain)).isEqualTo(jobId(theirs));
    }

    @Test
    void concurrentAddsOfTheSameJobCreateExactlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> addJob("Race Corp", "Race Engineer"));
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : pool.invokeAll(tasks)) {
                responses.add(future.get());
            }

            assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200, 201);
            assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
            assertThat(responses.stream().map(JobUniquenessIntegrationTest::jobId).distinct()).hasSize(1);
            assertThat(testData.count("SELECT count(*) FROM jobs WHERE company_key = 'race corp' AND title_key = 'race engineer' "
                    + "AND user_id = ?", testData.userId("test@jobhunter.local"))).isEqualTo(1);
            assertThat(testData.count("SELECT count(*) FROM companies WHERE name_key = 'race corp' AND user_id = ?",
                    testData.userId("test@jobhunter.local"))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void databaseRejectsDuplicateCompanyAndTitleKeysPerUserOnly() {
        long me = testData.userId("test@jobhunter.local");
        testData.createUserIfMissing("uniqueness-db-other@jobhunter.local", "other-password-123");
        long other = testData.userId("uniqueness-db-other@jobhunter.local");

        testData.insertRawJob(me, "Db Role", "db co", "db role");
        assertThatThrownBy(() -> testData.insertRawJob(me, "DB  ROLE", "db co", "db role"))
                .isInstanceOf(DuplicateKeyException.class);
        // The same identity for another user is a different job.
        testData.insertRawJob(other, "Db Role", "db co", "db role");
        assertThat(testData.count("SELECT count(*) FROM jobs WHERE company_key = 'db co' AND title_key = 'db role'")).isEqualTo(2);
    }

    private HttpResponse<String> addJob(String company, String title) throws Exception {
        String body = "{\"title\":" + JsonPathEscape.quote(title) + ",\"companyName\":" + JsonPathEscape.quote(company) + "}";
        return client.postJson("/api/board/jobs", body);
    }

    private int boardCardsForJob(int jobId) throws Exception {
        HttpResponse<String> board = client.get("/api/board");
        return (int) JsonPath.<List<Integer>>read(board.body(), "$[*].jobId").stream().filter(id -> id == jobId).count();
    }

    static int jobId(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.jobId");
    }

    static int applicationId(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.id");
    }

    private static final class JsonPathEscape {
        static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
        }
    }
}
