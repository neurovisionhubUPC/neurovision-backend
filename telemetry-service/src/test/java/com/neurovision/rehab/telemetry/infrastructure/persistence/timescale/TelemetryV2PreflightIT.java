package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Exercises the operator's unchanged SQL against isolated V1-shaped Timescale fixtures. */
@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
class TelemetryV2PreflightIT {
    @Inject Instance<DataSource> dataSource;

    @Test
    void acceptsDistinctSequencesWithoutChangingHistoricalRows() throws Exception {
        try (var connection = dataSource.get().getConnection()) {
            createV1InboxFixture(connection);
            UUID session = UUID.randomUUID();
            UUID stream = UUID.randomUUID();
            insert(connection, session, stream, 1);
            insert(connection, session, stream, 2);
            assertDoesNotThrow(() -> runPreflight(connection));
            assertEquals(2, countRows(connection));
        }
    }

    @Test
    void rejectsDuplicateStreamSequenceWithoutChangingHistoricalRows() throws Exception {
        try (var connection = dataSource.get().getConnection()) {
            createV1InboxFixture(connection);
            UUID session = UUID.randomUUID();
            UUID stream = UUID.randomUUID();
            insert(connection, session, stream, 1);
            insert(connection, session, stream, 1);
            var error = assertThrows(SQLException.class, () -> runPreflight(connection));
            assertTrue(error.getMessage().contains("V2 sequence preflight blocked"));
            assertFalse(error.getMessage().contains(session.toString()));
            assertFalse(error.getMessage().contains(stream.toString()));
            try (var rollback = connection.createStatement()) {
                rollback.execute("ROLLBACK");
            }
            assertEquals(2, countRows(connection));
        }
    }

    @Test
    void failsClosedWhenInboxCannotBeQueried() throws Exception {
        try (var connection = dataSource.get().getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS pg_temp.telemetry_batch_inbox");
            statement.execute("SET search_path TO pg_catalog");
            assertThrows(SQLException.class, () -> runPreflight(connection));
            statement.execute("ROLLBACK");
            statement.execute("RESET search_path");
        }
    }

    private void createV1InboxFixture(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS pg_temp.telemetry_batch_inbox");
            statement.execute("""
                    CREATE TEMP TABLE telemetry_batch_inbox (
                        batch_id UUID PRIMARY KEY,
                        therapy_session_id UUID NOT NULL,
                        stream_id UUID NOT NULL,
                        sequence_number BIGINT NOT NULL
                    ) ON COMMIT PRESERVE ROWS
                    """);
        }
    }

    private void insert(Connection connection, UUID session, UUID stream, long sequence) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO telemetry_batch_inbox
                    (batch_id, therapy_session_id, stream_id, sequence_number)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, session);
            insert.setObject(3, stream);
            insert.setLong(4, sequence);
            insert.executeUpdate();
        }
    }

    private void runPreflight(Connection connection) throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/preflight/V2__check_historical_stream_sequences.sql")) {
            if (stream == null) throw new IllegalStateException("Preflight SQL resource unavailable");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private long countRows(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM telemetry_batch_inbox")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
