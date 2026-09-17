package com.neurovision.rehab.telemetry.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationResult;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort.Publication;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import io.smallrye.reactive.messaging.kafka.Record;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;

class KafkaTelemetryBatchPublisherTest {
    private final Instant now = Instant.parse("2026-09-17T10:00:02Z");
    private final TelemetryBatch batch = new TelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 1, now.minusSeconds(2), now.minusSeconds(1),
            List.of(new TelemetryBatch.GazeSample(0, .2, .3, .9)), List.of());
    private final Publication publication = new Publication(TelemetryValidationResult.accepted(batch, now),
            UUID.randomUUID(), null);

    @Test
    void returnsSuccessOnlyOnCompletedBrokerAcknowledgment() throws Exception {
        var ack = new CompletableFuture<Void>();
        var captured = new AtomicReference<Record<String, String>>();
        var publisher = new KafkaTelemetryBatchPublisher(emitter(captured, ack), mapper(), 250);
        var worker = CompletableFuture.supplyAsync(() -> publisher.publish(publication));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (captured.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(captured.get() != null);
        assertFalse(worker.isDone());
        ack.complete(null);
        assertTrue(worker.join());
        assertEquals(batch.therapySessionId() + ":" + batch.streamId(), captured.get().key());
        var event = mapper().readTree(captured.get().value());
        assertEquals(batch.batchId().toString(), event.get("batchId").asText());
        assertEquals(1, event.get("schemaVersion").asInt());
        assertFalse(captured.get().value().contains("patientSubject"));
    }

    @Test
    void failureAndTimeoutNeverReportBrokerAcknowledgment() {
        var failed = new CompletableFuture<Void>();
        failed.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        assertFalse(new KafkaTelemetryBatchPublisher(emitter(new AtomicReference<>(), failed), mapper(), 100)
                .publish(publication));
        assertFalse(new KafkaTelemetryBatchPublisher(emitter(new AtomicReference<>(), new CompletableFuture<>()),
                mapper(), 10).publish(publication));
    }

    @SuppressWarnings("unchecked")
    private static Emitter<Record<String, String>> emitter(AtomicReference<Record<String, String>> captured,
            CompletionStage<Void> completion) {
        return (Emitter<Record<String, String>>) Proxy.newProxyInstance(Emitter.class.getClassLoader(),
                new Class<?>[] {Emitter.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("send")) {
                        captured.set((Record<String, String>) arguments[0]);
                        return completion;
                    }
                    return null;
                });
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
