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
                .containsExactly("1 initial schema true", "2 add stage scheduled end at true", "3 multiple integration tokens true",
                        "4 excluded companies true", "5 search prompts true");
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
    void theMultipleTokensMigrationKeepsEveryExistingTokenAsAnUnnamedTokenAndAllowsMore() throws Exception {
        String url = createDatabase("schema_tokens");
        assertThat(flyway(url, "2").migrate().success).isTrue();
        exec(url,
                "INSERT INTO users (email, password_hash, full_name) VALUES ('with-token@example.com', 'x', 'With')",
                "INSERT INTO users (email, password_hash, full_name) VALUES ('without-token@example.com', 'x', 'Without')",
                "INSERT INTO users (email, password_hash, full_name) VALUES ('second@example.com', 'x', 'Second')",
                "INSERT INTO integration_tokens (user_id, token_hash, token_suffix, created_at) SELECT id, '"
                        + "a".repeat(64) + "', 'aaaa', '2026-01-02 03:04:05.678901+00' FROM users WHERE email = 'with-token@example.com'",
                "INSERT INTO integration_tokens (user_id, token_hash, token_suffix) SELECT id, '"
                        + "b".repeat(64) + "', 'bbbb' FROM users WHERE email = 'second@example.com'");
        String tokens = "SELECT t.id || '|' || u.email || '|' || t.token_hash || '|' || t.token_suffix || '|' || t.created_at "
                + "FROM integration_tokens t JOIN users u ON u.id = t.user_id ORDER BY t.id";
        List<String> before = query(url, tokens);
        // The old schema allowed only one token per user.
        assertThatThrownBy(() -> exec(url, "INSERT INTO integration_tokens (user_id, token_hash, token_suffix) SELECT id, '"
                + "c".repeat(64) + "', 'cccc' FROM users WHERE email = 'with-token@example.com'"))
                .hasMessageContaining("uq_integration_tokens_user");

        assertThat(flyway(url).migrate().success).isTrue();

        // Same ids, owners, digests, suffixes and creation times: configured clients keep working.
        assertThat(query(url, tokens)).isEqualTo(before).hasSize(2);
        assertThat(query(url, "SELECT count(*) FROM integration_tokens WHERE name IS NULL AND regenerated_at IS NULL")).containsExactly("2");
        assertThat(query(url, "SELECT count(*) FROM integration_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.email = 'without-token@example.com'")).containsExactly("0");
        assertThat(query(url, "SELECT column_name || ' ' || data_type || ' ' || coalesce(character_maximum_length::text, '-') || ' ' "
                + "|| is_nullable FROM information_schema.columns WHERE table_name = 'integration_tokens' "
                + "AND column_name IN ('name', 'regenerated_at') ORDER BY 1"))
                .containsExactly("name character varying 100 YES", "regenerated_at timestamp with time zone - YES");
        assertThat(query(url, "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_integration_tokens_user'"))
                .singleElement().asString().contains("(user_id)");

        // Now several tokens per user, digests still unique, names never blank.
        exec(url, "INSERT INTO integration_tokens (user_id, token_hash, token_suffix, name) SELECT id, '"
                + "c".repeat(64) + "', 'cccc', 'Codex' FROM users WHERE email = 'with-token@example.com'");
        assertThat(query(url, "SELECT count(*) FROM integration_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.email = 'with-token@example.com'")).containsExactly("2");
        assertThatThrownBy(() -> exec(url, "INSERT INTO integration_tokens (user_id, token_hash, token_suffix) SELECT id, '"
                + "a".repeat(64) + "', 'aaaa' FROM users WHERE email = 'second@example.com'"))
                .hasMessageContaining("uq_integration_tokens_hash");
        assertThatThrownBy(() -> exec(url, "UPDATE integration_tokens SET name = '   ' WHERE token_suffix = 'aaaa'"))
                .hasMessageContaining("ck_integration_tokens_name_not_blank");
        // Deleting a user still removes exactly that user's tokens.
        exec(url, "DELETE FROM users WHERE email = 'with-token@example.com'");
        assertThat(query(url, "SELECT token_suffix FROM integration_tokens ORDER BY id")).containsExactly("bbbb");
    }

    @Test
    void theExcludedCompaniesMigrationIsAdditiveAndEnforcesOneCaseInsensitiveNamePerUser() throws Exception {
        String url = createDatabase("schema_excluded");
        assertThat(flyway(url, "3").migrate().success).isTrue();
        exec(url,
                "INSERT INTO users (email, password_hash, full_name) VALUES ('alice@example.com', 'x', 'Alice')",
                "INSERT INTO users (email, password_hash, full_name) VALUES ('bob@example.com', 'x', 'Bob')",
                "INSERT INTO companies (user_id, name, name_key) SELECT id, 'Flix', 'flix' FROM users WHERE email = 'alice@example.com'",
                "INSERT INTO jobs (user_id, company_id, title, company_key, title_key) "
                        + "SELECT c.user_id, c.id, 'Backend Engineer', 'flix', 'backend engineer' FROM companies c",
                "INSERT INTO applications (job_id, user_id, status_code) SELECT j.id, j.user_id, 'REJECTED' FROM jobs j");
        String board = "SELECT j.id || '|' || j.title || '|' || c.name || '|' || a.status_code || '|' || j.updated_at || '|' || a.updated_at "
                + "FROM jobs j JOIN companies c ON c.id = j.company_id JOIN applications a ON a.job_id = j.id ORDER BY j.id";
        List<String> boardBefore = query(url, board);

        assertThat(flyway(url).migrate().success).isTrue();

        // Nothing is pre-filled, not even from rejected jobs, and the Board is untouched.
        assertThat(query(url, "SELECT count(*) FROM excluded_companies")).containsExactly("0");
        assertThat(query(url, board)).isEqualTo(boardBefore).hasSize(1);
        exec(url, "INSERT INTO excluded_companies (user_id, name) SELECT id, 'Flix' FROM users WHERE email = 'alice@example.com'",
                "INSERT INTO excluded_companies (user_id, name) SELECT id, 'FLIX' FROM users WHERE email = 'bob@example.com'");
        assertThatThrownBy(() -> exec(url,
                "INSERT INTO excluded_companies (user_id, name) SELECT id, 'flix' FROM users WHERE email = 'alice@example.com'"))
                .hasMessageContaining("uq_excluded_companies_user_name_ignore_case");
        assertThatThrownBy(() -> exec(url,
                "INSERT INTO excluded_companies (user_id, name) SELECT id, ' PPRO' FROM users WHERE email = 'alice@example.com'"))
                .hasMessageContaining("ck_excluded_companies_name_trimmed");
        // Removing an excluded company leaves the job at that company alone.
        exec(url, "DELETE FROM excluded_companies");
        assertThat(query(url, board)).isEqualTo(boardBefore);
    }

    @Test
    void aFullDatabaseBackupAndRestoreKeepsEveryTokenWithItsOwner() throws Exception {
        String source = "backup_source_" + System.nanoTime();
        String target = "backup_target_" + System.nanoTime();
        exec(POSTGRES.getJdbcUrl(), "CREATE DATABASE " + source);
        String sourceUrl = urlOf(source);
        assertThat(flyway(sourceUrl).migrate().success).isTrue();
        exec(sourceUrl,
                "INSERT INTO users (email, password_hash, full_name) VALUES ('alice@example.com', 'x', 'Alice')",
                "INSERT INTO users (email, password_hash, full_name) VALUES ('bob@example.com', 'x', 'Bob')",
                "INSERT INTO integration_tokens (user_id, token_hash, token_suffix, name) SELECT id, '"
                        + "1".repeat(64) + "', '1111', 'Claude Desktop' FROM users WHERE email = 'alice@example.com'",
                "INSERT INTO integration_tokens (user_id, token_hash, token_suffix, regenerated_at) SELECT id, '"
                        + "2".repeat(64) + "', '2222', now() FROM users WHERE email = 'alice@example.com'",
                "INSERT INTO integration_tokens (user_id, token_hash, token_suffix, name) SELECT id, '"
                        + "3".repeat(64) + "', '3333', 'Codex' FROM users WHERE email = 'bob@example.com'");
        String tokens = "SELECT t.id || '|' || u.email || '|' || t.token_hash || '|' || t.token_suffix || '|' || coalesce(t.name, '-') "
                + "|| '|' || t.created_at || '|' || coalesce(t.regenerated_at::text, '-') "
                + "FROM integration_tokens t JOIN users u ON u.id = t.user_id ORDER BY t.id";
        List<String> before = query(sourceUrl, tokens);

        // The documented full backup: pg_dump of the whole database, restored with pg_restore into an empty one.
        inContainer("pg_dump", "-Fc -f /tmp/" + source + ".dump " + source);
        inContainer("createdb", target);
        inContainer("pg_restore", "--exit-on-error -d " + target + " /tmp/" + source + ".dump");
        String targetUrl = urlOf(target);

        assertThat(query(targetUrl, tokens)).isEqualTo(before).hasSize(3);
        assertThat(flyway(targetUrl).validateWithResult().validationSuccessful).isTrue();
        // The token table keeps its ownership cascade, unique digest, name check and owner index.
        String tokenTable = "SELECT conname || ' ' || contype::text FROM pg_constraint WHERE conrelid = 'integration_tokens'::regclass "
                + "UNION ALL SELECT indexdef FROM pg_indexes WHERE tablename = 'integration_tokens' ORDER BY 1";
        assertThat(query(targetUrl, tokenTable)).isEqualTo(query(sourceUrl, tokenTable))
                .contains("fk_integration_tokens_user f", "uq_integration_tokens_hash u", "ck_integration_tokens_name_not_blank c");
        // New tokens in the restored database continue the id sequence instead of colliding.
        exec(targetUrl, "INSERT INTO integration_tokens (user_id, token_hash, token_suffix) SELECT id, '"
                + "4".repeat(64) + "', '4444' FROM users WHERE email = 'bob@example.com'");
        assertThat(query(targetUrl, "SELECT count(*) FROM integration_tokens")).containsExactly("4");
    }

    @Test
    void everyTableWithAnOwnerRequiresOneAndIsDeletedWithIt() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        List<String> ownedTables = List.of("applications", "companies", "cvs", "excluded_companies", "integration_tokens", "job_matches", "jobs",
                "search_prompts", "settings");

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
        return urlOf(name);
    }

    private static String urlOf(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    /** Runs a PostgreSQL client tool inside the database container, as the test user. */
    private static void inContainer(String tool, String arguments) throws Exception {
        var result = POSTGRES.execInContainer("sh", "-c", "PGPASSWORD='" + POSTGRES.getPassword() + "' " + tool
                + " -U " + POSTGRES.getUsername() + " -h localhost " + arguments);
        assertThat(result.getExitCode()).as(tool + ": " + result.getStderr()).isZero();
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
