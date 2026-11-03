package dev.erezalkalay.jobhunter.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.erezalkalay.jobhunter.IntegrationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * The Flyway migrations on a database other than the application's own: an
 * empty database migrates to the same schema the application runs on (and
 * Hibernate validates at startup), and that schema ties every user-owned row
 * to its owner. Catalog-level counterpart of
 * {@link OwnershipConstraintsIntegrationTest}, so a table added without the
 * ownership rules fails here.
 */
class DatabaseSchemaIntegrationTest extends IntegrationTestBase {

    private static final String OWNER_FK = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE";

    @Test
    void anEmptyDatabaseMigratesToTheSchemaTheApplicationRunsOn() throws Exception {
        String url = createDatabase("schema_fresh");

        assertThat(flyway(url).migrate().success).isTrue();

        assertThat(query(url, "SELECT version || ' ' || description || ' ' || success FROM flyway_schema_history ORDER BY installed_rank"))
                .containsExactly("1 initial schema true", "2 add stage scheduled end at true");
        assertThat(schema(url)).isEqualTo(schema(POSTGRES.getJdbcUrl()));
        assertThat(String.join("\n", schema(url))).contains("jobs.user_id bigint NO", "uq_jobs_user_company_title",
                "FOREIGN KEY (job_id, user_id) REFERENCES jobs(id, user_id) ON DELETE CASCADE",
                "FOREIGN KEY (cv_id, user_id) REFERENCES cvs(id, user_id) ON DELETE SET NULL (cv_id)",
                "uq_users_email_ignore_case");
        assertThat(query(url, "SELECT code FROM application_statuses ORDER BY sort_order"))
                .containsExactly("NEW", "CV_SENT", "INTERVIEW_PROCESS", "OFFER", "REJECTED", "ARCHIVED", "LOW_MATCH");
    }

    @Test
    void theStageEndMigrationIsAdditiveAndLeavesExistingStagesUntouched() throws Exception {
        String url = createDatabase("schema_stage_end");
        assertThat(flyway(url, "1").migrate().success).isTrue();
        exec(url,
                "INSERT INTO users (email, password_hash, full_name) VALUES ('old@example.com', 'x', 'Old')",
                "INSERT INTO jobs (user_id, title, company_key, title_key) SELECT id, 'Role', '', 'role' FROM users",
                "INSERT INTO applications (job_id, user_id, status_code) SELECT j.id, j.user_id, 'NEW' FROM jobs j",
                "INSERT INTO application_stages (application_id, name, scheduled_at, interviewer_names, notes, outcome) "
                        + "SELECT id, 'Phone screen', '2026-10-29 13:00:00+00', 'Dana', 'Asked about Spring', 'Passed' FROM applications",
                "INSERT INTO application_stages (application_id, name) SELECT id, 'Unscheduled' FROM applications");
        String before = "SELECT name || '|' || coalesce(scheduled_at::text, '-') || '|' || coalesce(interviewer_names, '-') || '|' "
                + "|| coalesce(notes, '-') || '|' || coalesce(outcome, '-') || '|' || created_at || '|' || updated_at "
                + "FROM application_stages ORDER BY id";
        List<String> rowsBefore = query(url, before);

        assertThat(flyway(url).migrate().success).isTrue();

        assertThat(query(url, before)).isEqualTo(rowsBefore);
        assertThat(query(url, "SELECT count(*) FROM application_stages WHERE scheduled_end_at IS NULL")).containsExactly("2");
        assertThat(query(url, "SELECT data_type || ' ' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'application_stages' AND column_name = 'scheduled_end_at'"))
                .containsExactly("timestamp with time zone YES");
        // The database refuses an end that is not strictly after a start.
        assertThatThrownBy(() -> exec(url, "UPDATE application_stages SET scheduled_end_at = scheduled_at WHERE scheduled_at IS NOT NULL"))
                .hasMessageContaining("ck_application_stages_end_after_start");
        assertThatThrownBy(() -> exec(url, "UPDATE application_stages SET scheduled_end_at = now() WHERE scheduled_at IS NULL"))
                .hasMessageContaining("ck_application_stages_end_after_start");
    }

    @Test
    void everyTableWithAnOwnerRequiresOneAndIsDeletedWithIt() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        List<String> ownedTables = List.of("applications", "companies", "cvs", "integration_tokens", "job_matches", "jobs", "settings");

        assertThat(query(url, """
                SELECT table_name || ' ' || is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'user_id' ORDER BY 1"""))
                .containsExactlyElementsOf(ownedTables.stream().map(table -> table + " NO").toList());
        assertThat(query(url, "SELECT conrelid::regclass::text FROM pg_constraint "
                + "WHERE contype = 'f' AND pg_get_constraintdef(oid) = '" + OWNER_FK + "' ORDER BY 1"))
                .containsExactlyElementsOf(ownedTables);
    }

    @Test
    void childRowsCanOnlyReferenceTheSameUsersJobCompanyAndCv() throws Exception {
        assertThat(query(POSTGRES.getJdbcUrl(), """
                SELECT conrelid::regclass || ' ' || pg_get_constraintdef(oid) FROM pg_constraint
                WHERE contype = 'f' AND connamespace = 'public'::regnamespace AND array_length(conkey, 1) > 1
                ORDER BY 1"""))
                .containsExactly(
                        "applications FOREIGN KEY (job_id, user_id) REFERENCES jobs(id, user_id) ON DELETE CASCADE",
                        "job_matches FOREIGN KEY (cv_id, user_id) REFERENCES cvs(id, user_id) ON DELETE SET NULL (cv_id)",
                        "job_matches FOREIGN KEY (job_id, user_id) REFERENCES jobs(id, user_id) ON DELETE CASCADE",
                        "jobs FOREIGN KEY (company_id, user_id) REFERENCES companies(id, user_id)");
    }

    // --- helpers -------------------------------------------------------------

    private static String createDatabase(String prefix) throws Exception {
        String name = prefix + "_" + System.nanoTime();
        exec(POSTGRES.getJdbcUrl(), "CREATE DATABASE " + name);
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    private static Flyway flyway(String url) {
        return flyway(url, "latest");
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void exec(String url, String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> query(String url, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    /** Columns, constraints and indexes of the public schema (Flyway's own table excluded), as sorted text. */
    private static List<String> schema(String url) throws Exception {
        List<String> schema = new ArrayList<>(query(url, """
                SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable || ' '
                       || coalesce(character_maximum_length::text, '') || ' ' || coalesce(column_default, '')
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY 1"""));
        schema.addAll(query(url, """
                SELECT conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'flyway_schema_history'
                ORDER BY 1"""));
        schema.addAll(query(url, """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY 1"""));
        return schema;
    }
}
