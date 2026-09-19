package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler.Status;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort.AdmissionQuality;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmitTelemetryBatchEventHandlerTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();
    private static final TelemetryInboxPort.Provenance SOURCE =
            new TelemetryInboxPort.Provenance("telemetry-batch-accepted.v1", 0, 1, 300);

    @Test
    void classifiesInitialInOrderGapAndLateWithoutDiscardingValidBatches() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store);
        assertEquals(AdmissionQuality.INITIAL, admit(handler, batch(UUID.randomUUID(), 3, "a")).quality());
        assertEquals(AdmissionQuality.IN_ORDER, admit(handler, batch(UUID.randomUUID(), 4, "b")).quality());
        assertEquals(AdmissionQuality.GAP_DETECTED, admit(handler, batch(UUID.randomUUID(), 7, "c")).quality());
        assertEquals(AdmissionQuality.LATE_ARRIVAL, admit(handler, batch(UUID.randomUUID(), 5, "d")).quality());
        assertEquals(4, store.rows.size());
    }

    @Test
    void distinguishesDuplicateBatchConflictAndSequenceConflict() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store);
        var id = UUID.randomUUID();
        assertEquals(Status.ADMITTED, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.DUPLICATE, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.BATCH_CONFLICT, admit(handler, batch(id, 1, "changed")).status());
        assertEquals(Status.SEQUENCE_CONFLICT,
                admit(handler, batch(UUID.randomUUID(), 1, "other")).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void rereadsUniqueIndexesAfterConcurrentInsert() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store);
        var candidate = batch(UUID.randomUUID(), 2, "same");
        store.raceWith = candidate;
        assertEquals(Status.DUPLICATE, admit(handler, candidate).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void rereadsSequenceOccupantAfterConcurrentInsert() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store);
        var candidate = batch(UUID.randomUUID(), 2, "candidate");
        store.raceWith = batch(UUID.randomUUID(), 2, "occupant");
        assertEquals(Status.SEQUENCE_CONFLICT, admit(handler, candidate).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void treatsAnySequenceBelowMaximumLongAsLateWithoutOverflow() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store);
        assertEquals(AdmissionQuality.INITIAL,
                admit(handler, batch(UUID.randomUUID(), Long.MAX_VALUE, "maximum")).quality());
        assertEquals(AdmissionQuality.LATE_ARRIVAL,
                admit(handler, batch(UUID.randomUUID(), 0, "late")).quality());
    }

    private AdmitTelemetryBatchEventHandler.Result admit(
            AdmitTelemetryBatchEventHandler handler, AcceptedTelemetryBatch batch) {
        return handler.admit(batch, SOURCE).await().indefinitely();
    }

    private AcceptedTelemetryBatch batch(UUID id, long sequence, String hash) {
        var now = Instant.parse("2026-09-17T10:00:00Z");
        return new AcceptedTelemetryBatch(1, UUID.randomUUID(), id, SESSION, STREAM, sequence,
                now, now.plusSeconds(1), now.plusSeconds(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), hash);
    }

    private static final class MemoryInbox implements TelemetryInboxPort {
        private final List<AcceptedTelemetryBatch> rows = new ArrayList<>();
        private AcceptedTelemetryBatch raceWith;

        @Override public Uni<Void> prepare() { return Uni.createFrom().voidItem(); }
        @Override public Uni<Optional<StoredIdentity>> findByBatchId(UUID id) {
            return Uni.createFrom().item(rows.stream().filter(row -> row.batchId().equals(id)).findFirst()
                    .map(row -> new StoredIdentity(row.batchId(), row.payloadSha256())));
        }
        @Override public Uni<Optional<StoredIdentity>> findBySequence(UUID session, UUID stream, long sequence) {
            return Uni.createFrom().item(rows.stream().filter(row -> row.therapySessionId().equals(session)
                            && row.streamId().equals(stream) && row.sequence() == sequence).findFirst()
                    .map(row -> new StoredIdentity(row.batchId(), row.payloadSha256())));
        }
        @Override public Uni<Optional<Long>> maximumSequence(UUID session, UUID stream) {
            return Uni.createFrom().item(rows.stream().filter(row -> row.therapySessionId().equals(session)
                            && row.streamId().equals(stream)).map(AcceptedTelemetryBatch::sequence)
                    .max(Long::compare));
        }
        @Override public Uni<Void> insert(AcceptedTelemetryBatch batch, AdmissionQuality quality,
                Provenance source, Instant admittedAt) {
            if (raceWith != null) {
                rows.add(raceWith); raceWith = null;
                return Uni.createFrom().failure(new ConcurrentInsertException());
            }
            rows.add(batch);
            return Uni.createFrom().voidItem();
        }
    }
}
