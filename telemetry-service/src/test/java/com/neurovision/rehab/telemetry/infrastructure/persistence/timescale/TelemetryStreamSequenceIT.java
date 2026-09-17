package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryBatchPersistencePort.Result;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Uses real TimescaleDB to verify sequence locks, uniqueness, and quality metadata. */
@QuarkusTest
@QuarkusTestResource(value = TimescaleTestResource.class, restrictToAnnotatedClass = true)
class TelemetryStreamSequenceIT {
    @Inject PgTelemetryBatchPersistenceAdapter persistence;
    @Inject Instance<DataSource> dataSource;

    @Test
    void persistsGapsAndLateArrivalsWithoutMultiplyingOrReplacingSamples() throws Exception {
        UUID session = UUID.randomUUID();
        UUID stream = UUID.randomUUID();
        var first = batch(session, stream, 0);
        var gap = batch(session, stream, 3);
        var late = batch(session, stream, 1);
        var next = batch(session, stream, 4);

        assertEquals(Result.INSERTED, persist(first));
        assertEquals(Result.INSERTED, persist(gap));
        assertEquals(Result.INSERTED, persist(late));
        assertEquals(Result.INSERTED, persist(next));
        assertQuality(first.batchId(), "INITIAL", null, null);
        assertQuality(gap.batchId(), "GAP_DETECTED", 1L, 2L);
        assertQuality(late.batchId(), "LATE_ARRIVAL", null, null);
        assertQuality(next.batchId(), "IN_ORDER", null, null);
        assertEquals(4, countSamples(session, stream));
        assertEquals(4, highest(session, stream));

        assertEquals(Result.DUPLICATE, persist(gap));
        assertThrows(Exception.class, () -> persist(batch(session, stream, 3)));
        assertThrows(Exception.class, () -> persistence.persist(gap, Instant.now(), "b".repeat(64))
                .await().atMost(Duration.ofSeconds(10)));
        assertEquals(4, countInbox(session, stream));
        assertEquals(4, highest(session, stream));
    }

    @Test
    void concurrentSameSequenceHasOneWinner() throws Exception {
        UUID session = UUID.randomUUID();
        UUID stream = UUID.randomUUID();
        var a = batch(session, stream, 7);
        var b = batch(session, stream, 7);
        CompletableFuture<Object> first = persistence.persist(a, Instant.now(), "a".repeat(64))
                .subscribeAsCompletionStage().handle((result, error) -> error == null ? (Object) result : error)
                .toCompletableFuture();
        CompletableFuture<Object> second = persistence.persist(b, Instant.now(), "a".repeat(64))
                .subscribeAsCompletionStage().handle((result, error) -> error == null ? (Object) result : error)
                .toCompletableFuture();
        Object firstResult = first.get();
        Object secondResult = second.get();
        assertEquals(1, (firstResult == Result.INSERTED ? 1 : 0)
                + (secondResult == Result.INSERTED ? 1 : 0));
        assertTrue(firstResult instanceof Throwable || secondResult instanceof Throwable);
        assertEquals(1, countSamples(session, stream));
        assertEquals(1, countInbox(session, stream));
        assertEquals(7, highest(session, stream));
    }

    private TelemetryBatch batch(UUID session, UUID stream, long sequence) {
        Instant captured = Instant.now().minusSeconds(2);
        return new TelemetryBatch(1, UUID.randomUUID(), session, stream, sequence,
                captured, captured.plusSeconds(1),
                List.of(new TelemetryBatch.GazeSample(0, .2, .3, .9)), List.of());
    }

    private Result persist(TelemetryBatch batch) {
        return persistence.persist(batch, Instant.now(), "a".repeat(64))
                .await().atMost(Duration.ofSeconds(10));
    }

    private void assertQuality(UUID id, String quality, Long start, Long end) throws Exception {
        try (var connection = dataSource.get().getConnection(); var query = connection.prepareStatement("""
                SELECT sequence_quality, missing_sequence_start, missing_sequence_end
                FROM telemetry_batch_inbox WHERE batch_id = ?
                """)) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                rows.next();
                assertEquals(quality, rows.getString(1));
                assertEquals(start, rows.getObject(2, Long.class));
                assertEquals(end, rows.getObject(3, Long.class));
            }
        }
    }

    private long highest(UUID session, UUID stream) throws Exception {
        try (var connection = dataSource.get().getConnection(); var query = connection.prepareStatement("""
                SELECT highest_sequence_number FROM telemetry_stream_progress
                WHERE therapy_session_id = ? AND stream_id = ?
                """)) {
            query.setObject(1, session);
            query.setObject(2, stream);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private int countSamples(UUID session, UUID stream) throws Exception {
        return countFor("telemetry_samples", session, stream);
    }

    private int countInbox(UUID session, UUID stream) throws Exception {
        return countFor("telemetry_batch_inbox", session, stream);
    }

    private int countFor(String table, UUID session, UUID stream) throws Exception {
        try (var connection = dataSource.get().getConnection();
                var query = connection.prepareStatement("SELECT count(*) FROM " + table
                        + " WHERE therapy_session_id = ? AND stream_id = ?")) {
            query.setObject(1, session);
            query.setObject(2, stream);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
