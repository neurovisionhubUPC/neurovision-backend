package com.neurovision.rehab.telemetry.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryPublicationPort;
import com.neurovision.rehab.telemetry.interfaces.events.transform.TelemetryBatchEventAssembler;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;

/** Acknowledges HTTP ingestion only after the Kafka connector acknowledges the record. */
@ApplicationScoped
public class KafkaTelemetryBatchPublisher implements TelemetryPublicationPort {
    private final Emitter<Record<String, String>> emitter;
    private final ObjectMapper mapper;
    private final long acknowledgmentTimeoutMs;

    @Inject
    public KafkaTelemetryBatchPublisher(
            @Channel("telemetry-batch-accepted") @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 32)
                    Emitter<Record<String, String>> emitter,
            ObjectMapper mapper,
            @ConfigProperty(name = "telemetry.kafka.ack-timeout-ms") long acknowledgmentTimeoutMs) {
        if (acknowledgmentTimeoutMs <= 0) {
            throw new IllegalArgumentException("Kafka acknowledgment timeout must be positive");
        }
        this.emitter = emitter;
        this.mapper = mapper;
        this.acknowledgmentTimeoutMs = acknowledgmentTimeoutMs;
    }

    @Override
    public boolean publish(Publication acceptedBatch) {
        var event = TelemetryBatchEventAssembler.toV1(acceptedBatch);
        final String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException serializationFailure) {
            return false;
        }
        try {
            var record = Record.of(TelemetryBatchEventAssembler.partitionKey(acceptedBatch), payload);
            emitter.send(record).toCompletableFuture().get(acknowledgmentTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException unavailable) {
            // A timeout is ambiguous: the broker may still append this attempt later.
            return false;
        }
    }
}
