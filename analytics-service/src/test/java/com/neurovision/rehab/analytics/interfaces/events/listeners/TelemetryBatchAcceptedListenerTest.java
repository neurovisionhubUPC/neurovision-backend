package com.neurovision.rehab.analytics.interfaces.events.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

class TelemetryBatchAcceptedListenerTest {
    @Test
    void recordsAttemptsAndOneTerminalFailureWithoutChangingRetryOrAckNack() throws Exception {
        for (boolean failDlq : List.of(false, true)) {
            var inbox = new MemoryInbox(); inbox.fail = true;
            var projection = new RecordingProjection();
            var dlq = new RecordingDeadLetter(); dlq.fail = failDlq;
            var operations = new ArrayList<String>();
            var observer = new com.neurovision.rehab.analytics.application.internal.observability.PersistenceFailureObserver(
                    (operation, error) -> { operations.add(operation.name()); throw new IllegalStateException("observer unavailable"); });
            var handler = new AdmitAndProjectTelemetryBatchEventHandler(
                    new AdmitTelemetryBatchEventHandler(inbox, outcome -> {}, observer), projection);
            var listener = new TelemetryBatchAcceptedListener(handler, dlq, mapper, meters, 65536, 100, 60, 2, 1, 1000, observer);
            var ack = new AtomicBoolean(); var nack = new AtomicReference<Throwable>(); var event = event();
            listener.process(key(event), Message.of(mapper.writeValueAsString(event), () -> {
                ack.set(true); return CompletableFuture.completedFuture(null);
            }, error -> { nack.set(error); return CompletableFuture.completedFuture(null); })).toCompletableFuture().get();
            assertEquals(List.of("INBOX_PREPARE", "INBOX_PREPARE", "INBOX_PREPARE", "PIPELINE"), operations);
            assertEquals(3, inbox.attempts);
            assertEquals(!failDlq, ack.get());
            if (failDlq) assertEquals("Analytics DLQ unavailable", nack.get().getMessage());
            else assertNull(nack.get());
            assertEquals(AnalyticsDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED, dlq.record.get().code());
            assertTrue(projection.identities.isEmpty());
        }
    }

    @Test
    void reportsWholeFlowTimeoutAsPipelineWithoutGuessingTheMongoOperation() throws Exception {
        var operations = new ArrayList<String>();
        var observer = new com.neurovision.rehab.analytics.application.internal.observability.PersistenceFailureObserver(
                (operation, error) -> { operations.add(operation.name()); assertTrue(error instanceof io.smallrye.mutiny.TimeoutException); });
        var inbox = (TelemetryInboxPort) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TelemetryInboxPort.class}, (proxy, method, args) -> Uni.createFrom().nothing());
        var handler = new AdmitAndProjectTelemetryBatchEventHandler(
                new AdmitTelemetryBatchEventHandler(inbox, outcome -> {}, observer), new RecordingProjection());
        var dlq = new RecordingDeadLetter(); var event = event();
        var listener = new TelemetryBatchAcceptedListener(handler, dlq, mapper, meters, 65536, 100, 60, 0, 1, 100, observer);
        listener.process(key(event), Message.of(mapper.writeValueAsString(event))).toCompletableFuture().get();
        assertEquals(List.of("PIPELINE"), operations);
        assertEquals(AnalyticsDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED, dlq.record.get().code());
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private static final String DLQ_METRIC = "analytics.dlq.publications";

    @Test
    void countsSuccessfulPublicationOnlyOnCompletionAndBeforeAck() {
        var publication = new CompletableFuture<Void>();
        var acknowledgement = new CompletableFuture<Void>();
        var attempts = new AtomicInteger();
        var acks = new AtomicInteger();
        var nacks = new AtomicInteger();
        var listener = listener(new MemoryInbox(), new RecordingProjection(), failed -> {
            attempts.incrementAndGet();
            return publication;
        });
        var pending = listener.process("private-key", Message.of("private-payload", () -> {
            assertEquals(1, count("success"));
            assertEquals(0, count("failure"));
            acks.incrementAndGet();
            return acknowledgement;
        }, failure -> {
            nacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        })).toCompletableFuture();

        assertEquals(0, count("success"));
        assertFalse(pending.isDone());
        assertTrue(publication.complete(null));
        assertEquals(1, attempts.get());
        assertEquals(1, acks.get());
        assertEquals(0, nacks.get());
        assertFalse(pending.isDone());
        assertFalse(publication.complete(null));
        acknowledgement.completeExceptionally(new IllegalStateException("private ack detail"));
        assertThrows(CompletionException.class, pending::join);
        assertEquals(1, count("success"));
        assertEquals(0, count("failure"));
    }

    @Test
    void countsFailedPublicationOnceBeforeNackWithoutChangingItsStage() {
        var publication = new CompletableFuture<Void>();
        var negativeAcknowledgement = new CompletableFuture<Void>();
        var acks = new AtomicInteger();
        var nacks = new AtomicInteger();
        var listener = listener(new MemoryInbox(), new RecordingProjection(), failed -> publication);
        var pending = listener.process("private-key", Message.of("private-payload", () -> {
            acks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, failure -> {
            assertEquals(1, count("failure"));
            assertEquals("Analytics DLQ unavailable", failure.getMessage());
            nacks.incrementAndGet();
            return negativeAcknowledgement;
        })).toCompletableFuture();

        assertEquals(0, count("failure"));
        assertTrue(publication.completeExceptionally(new IllegalStateException("private publisher detail")));
        assertEquals(0, acks.get());
        assertEquals(1, nacks.get());
        assertFalse(pending.isDone());
        assertFalse(publication.completeExceptionally(new IllegalStateException("late failure")));
        negativeAcknowledgement.complete(null);
        pending.join();
        assertEquals(1, count("failure"));
        assertEquals(0, count("success"));
    }

    @Test
    void aggregatesAttemptsWithOnlyFixedOutcomeLabels() {
        var dlq = new RecordingDeadLetter();
        var listener = listener(new MemoryInbox(), new RecordingProjection(), dlq);
        listener.process("private-key-one", Message.of("private-payload-one")).toCompletableFuture().join();
        listener.process("private-key-two", Message.of("private-payload-two")).toCompletableFuture().join();
        dlq.fail = true;
        listener.process("private-key-three", Message.of("private-payload-three", () ->
                CompletableFuture.completedFuture(null), ignored -> CompletableFuture.completedFuture(null)))
                .toCompletableFuture().join();
        assertEquals(2, count("success"));
        assertEquals(1, count("failure"));
        assertEquals(2, meters.getMeters().size());
        for (var meter : meters.getMeters()) {
            assertEquals(DLQ_METRIC, meter.getId().getName());
            assertEquals(Meter.Type.COUNTER, meter.getId().getType());
            assertTrue(List.of(List.of(Tag.of("outcome", "success")), List.of(Tag.of("outcome", "failure")))
                    .contains(meter.getId().getTags()));
            assertFalse(meter.getId().toString().contains("private"));
        }
    }

    @Test
    void metricRegistrationFailureCannotChangeAckOrNack() {
        meters.config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("private metric detail");
            }
        });
        var dlq = new RecordingDeadLetter();
        var listener = listener(new MemoryInbox(), new RecordingProjection(), dlq);
        var acks = new AtomicInteger();
        var nacks = new AtomicInteger();
        var message = Message.of("private-payload", () -> {
            acks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, failure -> {
            nacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        listener.process("private-key", message).toCompletableFuture().join();
        dlq.fail = true;
        listener.process("private-key", message).toCompletableFuture().join();
        assertEquals(1, acks.get());
        assertEquals(1, nacks.get());
        assertEquals(0, meters.getMeters().size());
    }

    private double count(String outcome) {
        var counter = meters.find(DLQ_METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

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
        assertTrue(meters.getMeters().isEmpty());
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
        assertTrue(meters.getMeters().isEmpty());
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
        assertEquals(1, count("success"));
        assertEquals(0, count("failure"));
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
                new AdmitTelemetryBatchEventHandler(inbox, outcome -> {}), projection);
        return new TelemetryBatchAcceptedListener(handler, dlq,
                mapper, meters, 65536, 100, 60, 2, 1, 1000);
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
