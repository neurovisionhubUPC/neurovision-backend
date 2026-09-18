package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
class HistoricalTelemetryReadIT {
    @Inject Instance<DataSource> dataSource;
    @Inject PgHistoricalTelemetryReadAdapter reads;

    @Test
    void usesMigratedIndexAndPagesEqualTimeSamplesWithoutDuplicates() throws Exception {
        UUID session = UUID.randomUUID();
        UUID stream = UUID.randomUUID();
        UUID otherStream = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-17T12:00:00.123456Z");
        try (var connection = dataSource.get().getConnection()) {
            for (int i = 0; i < 3; i++) {
                insert(connection, session, stream, new UUID(0, i + 1), at, i);
            }
            insert(connection, session, otherStream, UUID.randomUUID(), at, 9);
            try (var statement = connection.createStatement();
                    var index = statement.executeQuery("SELECT indexname FROM pg_indexes "
                            + "WHERE indexname = 'telemetry_samples_replay_page_idx'")) {
                assertTrue(index.next());
            }
        }
        var first = reads.read(session, stream, at.minusSeconds(1), at.plusSeconds(1), null, 2);
        assertEquals(2, first.size());
        var second = reads.read(session, stream, at.minusSeconds(1), at.plusSeconds(1),
                first.getLast().cursor(), 2);
        assertEquals(1, second.size());
        assertEquals(2, second.getFirst().sequence());
        assertNotEquals(first.getLast().batchId(), second.getFirst().batchId());
        assertTrue(reads.read(session, stream, at.plusSeconds(1), at.plusSeconds(2), null, 2).isEmpty());
    }

    private static void insert(java.sql.Connection connection, UUID session, UUID stream,
            UUID batch, Instant time, long sequence) throws Exception {
        try (var insert = connection.prepareStatement("""
                INSERT INTO telemetry_samples (sample_time, batch_id, therapy_session_id, stream_id,
                    sequence_number, sample_kind, sample_index, offset_ms, server_received_at,
                    x, y, confidence)
                VALUES (?, ?, ?, ?, ?, 'GAZE', 0, 0, ?, 0.1, 0.2, 0.9)
                """)) {
            insert.setObject(1, java.time.OffsetDateTime.ofInstant(time, java.time.ZoneOffset.UTC));
            insert.setObject(2, batch);
            insert.setObject(3, session);
            insert.setObject(4, stream);
            insert.setLong(5, sequence);
            insert.setObject(6, java.time.OffsetDateTime.ofInstant(time, java.time.ZoneOffset.UTC));
            insert.executeUpdate();
        }
    }
}
