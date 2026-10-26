package com.lawground.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class QuestionSchemaIT {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private UUID owner;

    @BeforeAll
    static void migrate() {
        source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
    }

    @BeforeEach
    void member() {
        owner = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic schema member','ACTIVE')",
                owner);
    }

    private void insertQuestion(Connection connection, UUID id, String hash) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO questions(id,owner_id,subject,stem,question_type,provided_answer_number,reference_date,visibility,source_kind,answer_source,content_hash) VALUES (?,?,'BROKER_LAW','합성 원문','SELECT_CORRECT',2,'2025-10-25','PRIVATE','USER_INPUT','USER_PROVIDED',?)")) {
            statement.setObject(1, id);
            statement.setObject(2, owner);
            statement.setString(3, hash);
            statement.executeUpdate();
        }
    }

    private void choices(Connection connection, UUID id, int count) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO question_choices(question_id,choice_number,text) VALUES (?,?,'합성 선지')")) {
            for (int n = 1; n <= count; n++) {
                statement.setObject(1, id);
                statement.setInt(2, n);
                statement.executeUpdate();
            }
        }
    }

    private UUID validQuestion() throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertQuestion(connection, id, id.toString().replace("-", "").repeat(2));
            choices(connection, id, 5);
            connection.commit();
        }
        return id;
    }

    @Test
    void emptyDatabaseContainsVersionedProductTablesAndVector() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success AND type='SQL'",
                                Integer.class))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name",
                                String.class))
                .containsExactly(
                        "auth_identities",
                        "evaluation_results",
                        "evaluation_runs",
                        "evidence_results",
                        "flyway_schema_history",
                        "golden_case_evidences",
                        "golden_cases",
                        "golden_sets",
                        "law_articles",
                        "law_chunk_indexes",
                        "law_chunks",
                        "law_versions",
                        "laws",
                        "members",
                        "processing_jobs",
                        "question_choices",
                        "question_embeddings",
                        "questions",
                        "spring_session",
                        "spring_session_attributes",
                        "trace_runs");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extversion FROM pg_extension WHERE extname='vector'",
                                String.class))
                .isEqualTo("0.8.2");
    }

    @Test
    void existingV001UpgradesWithoutRewritingHistory() {
        var upgrade =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl()
                                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                                + "currentSchema=upgrade",
                        postgres.getUsername(),
                        postgres.getPassword());
        Flyway.configure()
                .dataSource(upgrade)
                .defaultSchema("upgrade")
                .target("1")
                .load()
                .migrate();
        var sql = new JdbcTemplate(upgrade);
        Integer checksum =
                sql.queryForObject(
                        "SELECT checksum FROM flyway_schema_history WHERE version='001' OR version='1'",
                        Integer.class);
        Flyway.configure().dataSource(upgrade).defaultSchema("upgrade").load().migrate();
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success AND type='SQL'",
                                Integer.class))
                .isEqualTo(7);
        assertThat(
                        sql.queryForObject(
                                "SELECT checksum FROM flyway_schema_history WHERE version='001' OR version='1'",
                                Integer.class))
                .isEqualTo(checksum);
        assertThat(sql.queryForObject("SELECT count(*) FROM question_choices", Integer.class))
                .isZero();
    }

    @Test
    void incompleteQuestionFailsAtCommitAndRollsBackAllRows() throws Exception {
        for (int count : new int[] {0, 4}) {
            UUID id = UUID.randomUUID();
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                insertQuestion(connection, id, id.toString().replace("-", "").repeat(2));
                choices(connection, id, count);
                assertThatThrownBy(connection::commit)
                        .isInstanceOf(SQLException.class)
                        .satisfies(
                                error ->
                                        assertThat(((SQLException) error).getSQLState())
                                                .isEqualTo("23514"));
                connection.rollback();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM questions WHERE id=?", Integer.class, id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM question_choices WHERE question_id=?",
                                    Integer.class,
                                    id))
                    .isZero();
        }
    }

    @Test
    void parentCannotLoseChoicesEvenWhenSoftDeleted() throws Exception {
        UUID id = validQuestion();
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", id);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM question_choices WHERE question_id=? AND choice_number=1",
                                        id))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_choices WHERE question_id=?",
                                Integer.class,
                                id))
                .isEqualTo(5);
    }

    @Test
    void constraintsRejectBadNumbersLengthsFkAndSourcePolicies() throws Exception {
        UUID id = validQuestion();
        for (String sql :
                new String[] {
                    "UPDATE questions SET provided_answer_number=6 WHERE id=?",
                    "UPDATE questions SET revision=0 WHERE id=?",
                    "UPDATE questions SET stem='  ' WHERE id=?",
                    "UPDATE questions SET stem=repeat('가',10001) WHERE id=?",
                    "UPDATE questions SET content_hash='bad' WHERE id=?",
                    "UPDATE questions SET visibility='PUBLIC' WHERE id=?",
                    "UPDATE questions SET source_metadata='[]'::jsonb WHERE id=?",
                    "UPDATE questions SET source_kind='EXAM_IMPORT' WHERE id=?",
                    "UPDATE question_choices SET text=repeat('가',2001) WHERE question_id=? AND choice_number=1",
                    "UPDATE question_choices SET choice_number=6 WHERE question_id=? AND choice_number=1",
                    "UPDATE question_choices SET choice_number=2 WHERE question_id=? AND choice_number=1"
                }) {
            assertThatThrownBy(() -> jdbc.update(sql, id))
                    .hasRootCauseInstanceOf(SQLException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE questions SET owner_id=? WHERE id=?",
                                        UUID.randomUUID(),
                                        id))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM members WHERE id=?", owner))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO question_choices VALUES (?,1,'합성')",
                                        UUID.randomUUID()))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void activeSourceKeysAreUniqueAndPublicRequiresOfficialVerification() throws Exception {
        UUID first = validQuestion();
        UUID second = validQuestion();
        jdbc.update(
                "UPDATE questions SET source_kind='EXAM_IMPORT',source_key='synthetic:source',source_url='https://example.invalid/synthetic',answer_source='OFFICIAL_VERIFIED',visibility='PUBLIC' WHERE id=?",
                first);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE questions SET source_kind='EXAM_IMPORT',source_key='synthetic:source',source_url='https://example.invalid/synthetic' WHERE id=?",
                                        second))
                .hasRootCauseInstanceOf(SQLException.class);
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", first);
        jdbc.update(
                "UPDATE questions SET source_kind='EXAM_IMPORT',source_key='synthetic:source',source_url='https://example.invalid/synthetic' WHERE id=?",
                second);
    }

    @Test
    void choiceMovesCheckBothParentsAndRollBack() throws Exception {
        UUID first = validQuestion();
        UUID second = validQuestion();
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "DELETE FROM question_choices WHERE question_id=? AND choice_number=1")) {
            connection.setAutoCommit(false);
            statement.setObject(1, second);
            statement.executeUpdate();
            try (var move =
                    connection.prepareStatement(
                            "UPDATE question_choices SET question_id=? WHERE question_id=? AND choice_number=1")) {
                move.setObject(1, second);
                move.setObject(2, first);
                move.executeUpdate();
            }
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
            connection.rollback();
        }
        for (UUID id : new UUID[] {first, second})
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM question_choices WHERE question_id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(5);
    }

    @Test
    void concurrentChoiceChangesSerializeBeforeMutation() throws Exception {
        UUID id = validQuestion();
        var attempted = new CountDownLatch(1);
        try (var first = source.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try (var update =
                    first.prepareStatement(
                            "UPDATE question_choices SET text='합성 변경1' WHERE question_id=? AND choice_number=1")) {
                update.setObject(1, id);
                update.executeUpdate();
            }
            var second =
                    pool.submit(
                            () -> {
                                try (var connection = source.getConnection();
                                        var update =
                                                connection.prepareStatement(
                                                        "UPDATE question_choices SET text='합성 변경2' WHERE question_id=? AND choice_number=2")) {
                                    connection.setAutoCommit(false);
                                    update.setObject(1, id);
                                    attempted.countDown();
                                    update.executeUpdate();
                                    connection.commit();
                                    return true;
                                }
                            });
            try {
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                // Wait for an observable database lock, not a timing-only assumption.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'UPDATE question_choices SET text=%'",
                                        Integer.class)
                                == 0
                        && System.nanoTime() < deadline) Thread.sleep(25);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'UPDATE question_choices SET text=%'",
                                        Integer.class))
                        .isGreaterThan(0);
                assertThat(second.isDone()).isFalse();
                first.commit();
                assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                first.rollback();
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_choices WHERE question_id=?",
                                Integer.class,
                                id))
                .isEqualTo(5);
    }

    @Test
    void dumpAndRestorePreserveDeferredTriggersAndData() throws Exception {
        UUID id = validQuestion();
        var dump =
                postgres.execInContainer(
                        "sh",
                        "-c",
                        "pg_dump -U test -d test --no-owner --no-privileges > /tmp/week2.sql");
        assertThat(dump.getExitCode()).isZero();
        assertThat(
                        postgres.execInContainer("createdb", "-U", "test", "restore_week2")
                                .getExitCode())
                .isZero();
        // A full pg_dump expects the new database's default public schema to exist.
        var restore =
                postgres.execInContainer(
                        "psql",
                        "-U",
                        "test",
                        "-d",
                        "restore_week2",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-f",
                        "/tmp/week2.sql");
        assertThat(restore.getExitCode())
                .withFailMessage("Synthetic dump restore failed: %s", restore.getStderr())
                .isZero();
        assertThat(
                        postgres.execInContainer(
                                        "psql",
                                        "-U",
                                        "test",
                                        "-d",
                                        "restore_week2",
                                        "-v",
                                        "ON_ERROR_STOP=1",
                                        "-c",
                                        "CREATE EXTENSION IF NOT EXISTS vector")
                                .getExitCode())
                .isZero();
        var restored =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl().replace("/test", "/restore_week2"),
                                postgres.getUsername(),
                                postgres.getPassword()));
        assertThat(
                        restored.queryForObject(
                                "SELECT count(*) FROM question_choices WHERE question_id=?",
                                Integer.class,
                                id))
                .isEqualTo(5);
        assertThatThrownBy(
                        () ->
                                restored.update(
                                        "DELETE FROM question_choices WHERE question_id=? AND choice_number=1",
                                        id))
                .hasRootCauseInstanceOf(SQLException.class);
    }
}
