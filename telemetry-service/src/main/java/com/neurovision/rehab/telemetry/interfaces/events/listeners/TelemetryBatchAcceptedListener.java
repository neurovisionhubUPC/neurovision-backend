package com.neurovision.rehab.telemetry.interfaces.events.listeners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.internal.eventhandlers.PersistTelemetryBatchEventHandler;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort.FailedRecord;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort.FailureCode;
import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryPersistenceRejectedException;
import com.neurovision.rehab.telemetry.interfaces.events.transform.TelemetryBatchPersistenceEventAssembler;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

/** Acknowledge only after persistence commits or a rejected record reaches the DLQ. */
@ApplicationScoped
public class TelemetryBatchAcceptedListener {
    private final PersistTelemetryBatchEventHandler handler;
    private final TelemetryDeadLetterPort deadLetter;
    private final ObjectMapper mapper;
    private final int maxEventBytes;
    private final int maxSamples;
    private final Duration futureSkew;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration persistenceTimeout;

    @Inject
    public TelemetryBatchAcceptedListener(PersistTelemetryBatchEventHandler handler,
            TelemetryDeadLetterPort deadLetter, ObjectMapper mapper,
            @ConfigProperty(name = "telemetry.consumer.max-event-bytes") int maxEventBytes,
            @ConfigProperty(name = "telemetry.ingress.max-samples") int maxSamples,
            @ConfigProperty(name = "telemetry.ingress.max-future-skew-seconds") long futureSkewSeconds,
            @ConfigProperty(name = "telemetry.consumer.max-retries") int maxRetries,
            @ConfigProperty(name = "telemetry.consumer.retry-delay-ms") long retryDelayMs,
            @ConfigProperty(name = "telemetry.consumer.persistence-timeout-ms") long persistenceTimeoutMs) {
        if (maxRetries < 0 || maxRetries > 3 || retryDelayMs < 1 || retryDelayMs > 1000
                || persistenceTimeoutMs < 100 || persistenceTimeoutMs > 10_000) {
            throw new IllegalArgumentException("Invalid telemetry consumer bounds");
        }
        this.handler = handler;
        this.deadLetter = deadLetter;
        this.mapper = mapper;
        this.maxEventBytes = maxEventBytes;
        this.maxSamples = maxSamples;
        this.futureSkew = Duration.ofSeconds(futureSkewSeconds);
        this.maxRetries = maxRetries;
        this.retryDelay = Duration.ofMillis(retryDelayMs);
        this.persistenceTimeout = Duration.ofMillis(persistenceTimeoutMs);
    }

    @Incoming("telemetry-batch-persist")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public CompletionStage<Void> consume(Message<String> message) {
        var metadata = message.getMetadata(IncomingKafkaRecordMetadata.class);
        String key = metadata.map(value -> (String) value.getKey()).orElse(null);
        return process(key, message,
                metadata.map(IncomingKafkaRecordMetadata::getTopic).orElse("telemetry-batch-accepted.v1"),
                metadata.map(IncomingKafkaRecordMetadata::getPartition).orElse(-1),
                metadata.map(IncomingKafkaRecordMetadata::getOffset).orElse(-1L));
    }

    CompletionStage<Void> process(String key, Message<String> message) {
        return process(key, message, "telemetry-batch-accepted.v1", -1, -1);
    }

    private CompletionStage<Void> process(String key, Message<String> message,
            String topic, int partition, long offset) {
        try {
            var accepted = TelemetryBatchPersistenceEventAssembler.decode(key, message.getPayload(),
                    mapper, maxEventBytes, maxSamples, futureSkew, Instant.now());
            return Uni.createFrom().deferred(() -> handler.persist(
                            accepted.batch(), accepted.serverReceivedAt(), accepted.payloadSha256()))
                    .ifNoItem().after(persistenceTimeout).fail()
                    .onFailure(failure -> !isPermanent(failure)).retry()
                    .withBackOff(retryDelay, retryDelay.multipliedBy(4)).atMost(maxRetries)
                    .subscribeAsCompletionStage()
                    .handle((ignored, failure) -> failure == null
                            ? message.ack()
                            : quarantine(key, message, isPermanent(failure)
                                    ? FailureCode.PERSISTENCE_REJECTED
                                    : FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                                    topic, partition, offset))
                    .thenCompose(Function.identity());
        } catch (RuntimeException rejected) {
            return quarantine(key, message, FailureCode.INVALID_EVENT, topic, partition, offset);
        }
    }

    private CompletionStage<Void> quarantine(String key, Message<String> message, FailureCode code,
            String topic, int partition, long offset) {
        return deadLetter.quarantine(new FailedRecord(key, message.getPayload(), code, topic, partition, offset))
                .handle((ignored, failure) -> failure == null
                        ? message.ack()
                        : message.nack(new IllegalStateException("Telemetry DLQ unavailable")))
                .thenCompose(Function.identity());
    }

    private boolean isPermanent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TelemetryPersistenceRejectedException) return true;
        }
        return false;
    }
}
