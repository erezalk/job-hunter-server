package dev.erezalkalay.jobhunter.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database itself refuses cross-user ownership, independently of the Java
 * code: composite foreign keys tie a ticket, an AI match, a company link and a
 * match's CV to the job owner's rows. Deleting a user removes exactly that
 * user's data (the deletion graph is ownership, never shared rows).
 */
class OwnershipConstraintsIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "constraints-password-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    private long userA;
    private long userB;
    private long jobA;
    private long jobB;
    private TestApiClient a;
    private TestApiClient b;

    @BeforeEach
    void setUp() throws Exception {
        String run = System.nanoTime() + "-" + SEQ.incrementAndGet();
        a = login("constraints-a-" + run + "@jobhunter.local");
        b = login("constraints-b-" + run + "@jobhunter.local");
        userA = testData.userId("constraints-a-" + run + "@jobhunter.local");
        userB = testData.userId("constraints-b-" + run + "@jobhunter.local");
        jobA = jobId(a.postJson("/api/board/jobs", "{\"title\":\"Constraint Role\",\"companyName\":\"Constraint Co\"}"));
        jobB = jobId(b.postJson("/api/board/jobs", "{\"title\":\"Constraint Role\",\"companyName\":\"Constraint Co\"}"));
    }

    @Test
    void aTicketCannotPointAtAnotherUsersJobAndAJobHasOnlyOneTicket() {
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO applications (job_id, user_id, status_code) VALUES (?, ?, 'NEW')", jobA, userB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO applications (job_id, user_id, status_code) VALUES (?, ?, 'NEW')", jobA, userA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aMatchCannotBelongToAnyoneButTheJobOwnerAndOnlyUsesTheOwnersCv() throws Exception {
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO job_matches (job_id, user_id, match_score) VALUES (?, ?, 50)", jobA, userB))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(b.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("B's CV with plenty of text to count as extracted.")).statusCode()).isEqualTo(201);
        long cvB = Long.parseLong(testData.strings("SELECT id::text FROM cvs WHERE user_id = ? AND is_primary", userB).get(0));
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO job_matches (job_id, user_id, cv_id, match_score) VALUES (?, ?, ?, 50)", jobA, userA, cvB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO job_matches (job_id, user_id, match_score) VALUES (?, ?, 101)", jobA, userA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aJobCannotUseAnotherUsersCompanyAndCompanyNamesArePerUser() {
        long companyB = Long.parseLong(testData.strings("SELECT company_id::text FROM jobs WHERE id = ?", jobB).get(0));
        assertThatThrownBy(() -> testData.update("UPDATE jobs SET company_id = ? WHERE id = ?", companyB, jobA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO companies (user_id, name, name_key) VALUES (?, 'CONSTRAINT CO', 'constraint co')", userA))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A company in use cannot be deleted out from under its jobs.
        assertThatThrownBy(() -> testData.update("DELETE FROM companies WHERE id = ?", companyB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(testData.count("SELECT count(*) FROM companies WHERE name_key = 'constraint co' AND user_id IN (?, ?)",
                userA, userB)).isEqualTo(2);
    }

    @Test
    void ownerColumnsCannotBeLeftEmpty() {
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO jobs (title, company_key, title_key) VALUES ('No Owner', '', 'no owner')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO companies (name, name_key) VALUES ('No Owner Co', 'no owner co')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailsAreUniqueIgnoringCaseSoAPrincipalResolvesToExactlyOneUser() {
        String email = testData.strings("SELECT email FROM users WHERE id = ?", userA).get(0);
        assertThatThrownBy(() -> testData.update(
                "INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', 'Shadow')", email.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserRemovesExactlyTheirDataAndNothingOfAnyoneElse() throws Exception {
        long applicationA = Long.parseLong(testData.strings("SELECT id::text FROM applications WHERE job_id = ?", jobA).get(0));
        a.postJson("/api/applications/" + applicationA + "/stages", "{\"name\":\"Stage\"}");
        testData.createJobMatch(jobA, 70);
        testData.createJobMatch(jobB, 71);
        a.postJson("/api/settings/integrations/mcp-token", "{}");
        int othersBefore = othersRowCount();

        testData.update("DELETE FROM users WHERE id = ?", userA);

        for (String table : new String[] {"jobs", "companies", "applications", "job_matches", "cvs", "settings", "integration_tokens"}) {
            assertThat(testData.count("SELECT count(*) FROM " + table + " WHERE user_id = ?", userA)).as(table).isZero();
        }
        assertThat(testData.count("SELECT count(*) FROM application_stages s JOIN applications a ON a.id = s.application_id "
                + "WHERE a.job_id = ?", jobA)).isZero();
        assertThat(testData.count("SELECT count(*) FROM application_status_history WHERE application_id = ?", applicationA)).isZero();
        assertThat(othersRowCount()).isEqualTo(othersBefore);
        assertThat(testData.count("SELECT count(*) FROM job_matches WHERE job_id = ?", jobB)).isEqualTo(1);
    }

    private int othersRowCount() {
        return testData.count("SELECT (SELECT count(*) FROM jobs WHERE user_id <> ?) + (SELECT count(*) FROM companies WHERE user_id <> ?)"
                + " + (SELECT count(*) FROM applications WHERE user_id <> ?) + (SELECT count(*) FROM job_matches WHERE user_id <> ?)"
                + " + (SELECT count(*) FROM application_status_history h JOIN applications a ON a.id = h.application_id WHERE a.user_id <> ?)",
                userA, userA, userA, userA, userA);
    }

    private TestApiClient login(String email) throws Exception {
        testData.createUserIfMissing(email, PASSWORD);
        TestApiClient api = new TestApiClient(port);
        assertThat(api.login(email, PASSWORD).statusCode()).isEqualTo(200);
        return api;
    }

    private static long jobId(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.<Number>read(response.body(), "$.jobId").longValue();
    }
}
