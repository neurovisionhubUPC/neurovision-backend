package com.neurovision.rehab.telemetry.interfaces.events.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.telemetry.application.internal.eventhandlers.PersistTelemetryBatchEventHandler;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryBatchPersistencePort;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryPersistenceRejectedException;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

class TelemetryBatchAcceptedListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void acknowledgesOnlyAfterPersistenceCompletion() throws Exception {
        var committed = new CompletableFuture<TelemetryBatchPersistencePort.Result>();
        TelemetryBatchPersistencePort persistence = (batch, receivedAt, hash) ->
                Uni.createFrom().completionStage(committed);
        var dlq = new RecordingDeadLetter();
        var event = event();
        var acknowledged = new AtomicBoolean();
        var processing = listener(persistence, dlq).process(key(event),
                Message.of(mapper.writeValueAsString(event), () -> {
                    acknowledged.set(true);
                    return CompletableFuture.completedFuture(null);
                }));
        assertFalse(acknowledged.get());
        committed.complete(TelemetryBatchPersistencePort.Result.INSERTED);
        processing.toCompletableFuture().get();
        assertTrue(acknowledged.get());
        assertNull(dlq.record.get());
    }

    @Test
    void retriesTransientFailureThenAcknowledgesCommittedBatch() throws Exception {
        var attempts = new AtomicInteger();
        TelemetryBatchPersistencePort persistence = (batch, receivedAt, hash) ->
                attempts.incrementAndGet() < 3
                        ? Uni.createFrom().failure(new IllegalStateException("sensitive database details"))
                        : Uni.createFrom().item(TelemetryBatchPersistencePort.Result.INSERTED);
        var dlq = new RecordingDeadLetter();
        var event = event();
        var ack = new AtomicBoolean();
        listener(persistence, dlq).process(key(event), Message.of(mapper.writeValueAsString(event), () -> {
            ack.set(true);
            return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().get();
        assertEquals(3, attempts.get());
        assertTrue(ack.get());
        assertNull(dlq.record.get());
    }

    @Test
    void exhaustedFailureAndPermanentConflictUseSafeDlqCodes() throws Exception {
        var attempts = new AtomicInteger();
        TelemetryBatchPersistencePort transientFailure = (batch, receivedAt, hash) -> {
            attempts.incrementAndGet();
            return Uni.createFrom().failure(new IllegalStateException("sensitive database details"));
        };
        var dlq = new RecordingDeadLetter();
        var event = event();
        var ack = new AtomicBoolean();
        listener(transientFailure, dlq).process(key(event), Message.of(mapper.writeValueAsString(event), () -> {
            assertTrue(dlq.record.get() != null, "DLQ acknowledgement precedes source acknowledgement");
            ack.set(true);
            return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().get();
        assertEquals(3, attempts.get());
        assertTrue(ack.get());
        assertEquals(TelemetryDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                dlq.record.get().code());

        TelemetryBatchPersistencePort conflict = (batch, receivedAt, hash) -> {
            attempts.incrementAndGet();
            return Uni.createFrom().failure(new TelemetryPersistenceRejectedException());
        };
        listener(conflict, dlq).process(key(event), Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get();
        assertEquals(4, attempts.get(), "Permanent failures must not be retried");
        assertEquals(TelemetryDeadLetterPort.FailureCode.PERSISTENCE_REJECTED, dlq.record.get().code());
        listener(conflict, dlq).process("wrong-key", Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get();
        assertEquals(4, attempts.get());
        assertEquals(TelemetryDeadLetterPort.FailureCode.INVALID_EVENT, dlq.record.get().code());
    }

    @Test
    void dlqFailureNacksWithoutAcknowledgingOrLeakingPayload() {
        TelemetryBatchPersistencePort persistence = (batch, receivedAt, hash) ->
                Uni.createFrom().failure(new TelemetryPersistenceRejectedException());
        var dlq = new RecordingDeadLetter();
        dlq.fail = true;
        var ack = new AtomicBoolean();
        var nack = new AtomicReference<Throwable>();
        var payload = "private-medical-payload";
        listener(persistence, dlq).process("key", Message.of(payload, () -> {
            ack.set(true);
            return CompletableFuture.completedFuture(null);
        }, failure -> {
            nack.set(failure);
            return CompletableFuture.completedFuture(null);
        })).toCompletableFuture().join();
        assertFalse(ack.get());
        assertEquals("Telemetry DLQ unavailable", nack.get().getMessage());
        assertFalse(nack.get().toString().contains(payload));
        assertNull(nack.get().getCause());
    }

    @Test
    void stalledPersistenceHasFiniteTimeoutAndRetryBudget() throws Exception {
        var attempts = new AtomicInteger();
        TelemetryBatchPersistencePort stalled = (batch, receivedAt, hash) -> {
            attempts.incrementAndGet();
            return Uni.createFrom().completionStage(new CompletableFuture<>());
        };
        var dlq = new RecordingDeadLetter();
        var event = event();
        listener(stalled, dlq).process(key(event), Message.of(mapper.writeValueAsString(event)))
                .toCompletableFuture().get(6, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3, attempts.get());
        assertEquals(TelemetryDeadLetterPort.FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                dlq.record.get().code());
    }

    private TelemetryBatchAcceptedListener listener(TelemetryBatchPersistencePort persistence,
            TelemetryDeadLetterPort dlq) {
        return new TelemetryBatchAcceptedListener(new PersistTelemetryBatchEventHandler(persistence),
                dlq, mapper, 65536, 100, 60, 2, 1, 1000);
    }

    private TelemetryBatchAcceptedV1 event() {
        var now = Instant.now();
        return new TelemetryBatchAcceptedV1(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0, now.minusSeconds(2), now.minusSeconds(1),
                now, UUID.randomUUID(), "trace",
                List.of(new TelemetryBatchAcceptedV1.GazeSampleV1(0, .2, .3, .9)), List.of());
    }

    private String key(TelemetryBatchAcceptedV1 event) {
        return event.therapySessionId() + ":" + event.streamId();
    }

    private static final class RecordingDeadLetter implements TelemetryDeadLetterPort {
        private final AtomicReference<FailedRecord> record = new AtomicReference<>();
        private boolean fail;

        @Override
        public CompletableFuture<Void> quarantine(FailedRecord failed) {
            record.set(failed);
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("private broker details"))
                    : CompletableFuture.completedFuture(null);
        }
    }
}
