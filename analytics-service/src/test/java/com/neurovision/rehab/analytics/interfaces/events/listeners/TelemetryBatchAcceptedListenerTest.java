package com.neurovision.rehab.analytics.interfaces.events.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitAndProjectTelemetryBatchEventHandler;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler;
import com.neurovision.rehab.analytics.application.ports.in.ProjectTelemetryStreamUseCase;
import com.neurovision.rehab.analytics.application.ports.out.messaging.AnalyticsDeadLetterPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import com.neurovision.rehab.analytics.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

class TelemetryBatchAcceptedListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void acknowledgesAdmittedAndDuplicateOnlyAfterInboxResult() throws Exception {
        var inbox = new MemoryInbox();
        var projection = new RecordingProjection();
        var dlq = new RecordingDeadLetter();
        var event = event();
        var ack = new AtomicBoolean();
        listener(inbox, projection, dlq).process(key(event), Message.of(mapper.writeValueAsString(event), () -> {
            ack.set(true); return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().get();
        assertTrue(ack.get()); assertNull(dlq.record.get());

        ack.set(false);
        var retry = new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), event.batchId(),
                event.therapySessionId(), event.streamId(), event.sequence(), event.capturedAt(), event.sentAt(),
                Instant.now(), UUID.randomUUID(), null, event.gazeSamples(), event.gameplaySamples());
        listener(inbox, projection, dlq).process(key(event), Message.of(mapper.writeValueAsString(retry), () -> {
            ack.set(true); return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().get();
        assertTrue(ack.get()); assertEquals(1, inbox.rows.size());
        assertEquals(2, projection.identities.size());
    }

    @Test
    void quarantinesInvalidAndConflictingRecordsBeforeAck() throws Exception {
        var inbox = new MemoryInbox(); var projection = new RecordingProjection();
        var dlq = new RecordingDeadLetter(); var event = event();
        listener(inbox, projection, dlq).process("wrong", Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get();
        assertEquals(AnalyticsDeadLetterPort.FailureCode.INVALID_EVENT, dlq.record.get().code());

        listener(inbox, projection, dlq).process(key(event), Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get();
        assertEquals(1, projection.identities.size());
        var changed = new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), event.batchId(),
                event.therapySessionId(), event.streamId(), event.sequence(), event.capturedAt(), event.sentAt(),
                Instant.now(), UUID.randomUUID(), null,
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, .8, .3, .9)), List.of());
        var ack = new AtomicBoolean();
        listener(inbox, projection, dlq).process(key(event), Message.of(mapper.writeValueAsString(changed), () -> {
            assertEquals(AnalyticsDeadLetterPort.FailureCode.BATCH_CONFLICT, dlq.record.get().code());
            ack.set(true); return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().get();
        assertTrue(ack.get()); assertEquals(1, inbox.rows.size());
        assertEquals(1, projection.identities.size());

        var sequenceConflict = new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), UUID.randomUUID(),
                event.therapySessionId(), event.streamId(), event.sequence(), event.capturedAt(), event.sentAt(),
                Instant.now(), UUID.randomUUID(), null, event.gazeSamples(), event.gameplaySamples());
        listener(inbox, projection, dlq).process(key(event),
                Message.of(mapper.writeValueAsString(sequenceConflict))).toCompletableFuture().get();
        assertEquals(AnalyticsDeadLetterPort.FailureCode.SEQUENCE_CONFLICT, dlq.record.get().code());
        assertEquals(1, projection.identities.size());
    }

    @Test
    void retriesTheWholeFlowAndRecoversProjectionAfterInboxAdmission() throws Exception {
        var inbox = new MemoryInbox();
        var projection = new RecordingProjection(); projection.failuresRemaining = 1;
        var dlq = new RecordingDeadLetter(); var event = event(); var ack = new AtomicBoolean();

        listener(inbox, projection, dlq).process(key(event),
                Message.of(mapper.writeValueAsString(event), () -> {
                    ack.set(true); return CompletableFuture.completedFuture(null);
                })).toCompletableFuture().get();

        assertTrue(ack.get());
        assertEquals(1, inbox.rows.size());
        assertEquals(2, projection.identities.size());
        assertNull(dlq.record.get());
    }

    @Test
    void exhaustsProjectionRetriesBeforeQuarantineAndAcknowledgement() throws Exception {
        var inbox = new MemoryInbox();
        var projection = new RecordingProjection(); projection.failuresRemaining = 3;
        var dlq = new RecordingDeadLetter(); var event = event(); var ack = new AtomicBoolean();

        listener(inbox, projection, dlq).process(key(event),
                Message.of(mapper.writeValueAsString(event), () -> {
                    assertEquals(AnalyticsDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                            dlq.record.get().code());
                    ack.set(true); return CompletableFuture.completedFuture(null);
                })).toCompletableFuture().get();

        assertTrue(ack.get());
        assertEquals(1, inbox.rows.size());
        assertEquals(3, projection.identities.size());
    }

    @Test
    void exhaustedMongoFailureUsesDlqAndDlqFailureNacksWithoutPayloadLeak() throws Exception {
        var inbox = new MemoryInbox(); inbox.fail = true;
        var projection = new RecordingProjection();
        var dlq = new RecordingDeadLetter(); var event = event();
        listener(inbox, projection, dlq).process(key(event), Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get();
        assertEquals(3, inbox.attempts);
        assertEquals(AnalyticsDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                dlq.record.get().code());

        dlq.fail = true; var ack = new AtomicBoolean(); var nack = new AtomicReference<Throwable>();
        listener(inbox, projection, dlq).process("wrong", Message.of("private-coordinate-payload", () -> {
            ack.set(true); return CompletableFuture.completedFuture(null);
        }, failure -> { nack.set(failure); return CompletableFuture.completedFuture(null); }))
                .toCompletableFuture().join();
        assertFalse(ack.get()); assertEquals("Analytics DLQ unavailable", nack.get().getMessage());
        assertFalse(nack.get().toString().contains("private-coordinate-payload"));
    }

    private TelemetryBatchAcceptedListener listener(TelemetryInboxPort inbox,
            ProjectTelemetryStreamUseCase projection, AnalyticsDeadLetterPort dlq) {
        var handler = new AdmitAndProjectTelemetryBatchEventHandler(
                new AdmitTelemetryBatchEventHandler(inbox), projection);
        return new TelemetryBatchAcceptedListener(handler, dlq,
                mapper, 65536, 100, 60, 2, 1, 1000);
    }

    private TelemetryBatchAcceptedV1 event() {
        var now = Instant.now();
        return new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, now.minusSeconds(2), now.minusSeconds(1), now,
                UUID.randomUUID(), "trace",
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, .2, .3, .9)), List.of());
    }

    private String key(TelemetryBatchAcceptedV1 event) {
        return event.therapySessionId() + ":" + event.streamId();
    }

    private static final class RecordingDeadLetter implements AnalyticsDeadLetterPort {
        private final AtomicReference<FailedRecord> record = new AtomicReference<>();
        private boolean fail;
        @Override public CompletableFuture<Void> quarantine(FailedRecord failed) {
            record.set(failed);
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("private details"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingProjection implements ProjectTelemetryStreamUseCase {
        private final List<TelemetryStreamIdentity> identities = new ArrayList<>();
        private int failuresRemaining;

        @Override public Uni<Void> project(TelemetryStreamIdentity identity) {
            identities.add(identity);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return Uni.createFrom().failure(new IllegalStateException("private projection details"));
            }
            return Uni.createFrom().voidItem();
        }
    }

    private static final class MemoryInbox implements TelemetryInboxPort {
        private final List<AcceptedTelemetryBatch> rows = new ArrayList<>();
        private boolean fail; private int attempts;
        @Override public Uni<Void> prepare() {
            attempts++;
            return fail ? Uni.createFrom().failure(new IllegalStateException("private mongo details"))
                    : Uni.createFrom().voidItem();
        }
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
        @Override public Uni<Void> insert(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality,
                Provenance source, Instant admittedAt) {
            rows.add(batch); return Uni.createFrom().voidItem();
        }
    }
}
