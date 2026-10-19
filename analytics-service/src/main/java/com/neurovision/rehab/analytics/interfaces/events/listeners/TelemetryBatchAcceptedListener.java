package com.neurovision.rehab.analytics.interfaces.events.listeners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.observability.PersistenceFailureObserver;
import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort.Operation;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitAndProjectTelemetryBatchEventHandler;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler.Status;
import com.neurovision.rehab.analytics.application.ports.out.messaging.AnalyticsDeadLetterPort;
import com.neurovision.rehab.analytics.application.ports.out.messaging.AnalyticsDeadLetterPort.FailedRecord;
import com.neurovision.rehab.analytics.application.ports.out.messaging.AnalyticsDeadLetterPort.FailureCode;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort.Provenance;
import com.neurovision.rehab.analytics.interfaces.events.transform.TelemetryBatchAcceptedV1Decoder;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;

@ApplicationScoped
public class TelemetryBatchAcceptedListener {
    private final AdmitAndProjectTelemetryBatchEventHandler handler;
    private final AnalyticsDeadLetterPort deadLetter;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final int maxEventBytes;
    private final int maxSamples;
    private final Duration futureSkew;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration persistenceTimeout;
    private final PersistenceFailureObserver failures;

    @Inject
    public TelemetryBatchAcceptedListener(AdmitAndProjectTelemetryBatchEventHandler handler,
            AnalyticsDeadLetterPort deadLetter, ObjectMapper mapper, MeterRegistry meters,
            @ConfigProperty(name = "analytics.consumer.max-event-bytes") int maxEventBytes,
            @ConfigProperty(name = "analytics.consumer.max-samples") int maxSamples,
            @ConfigProperty(name = "analytics.consumer.max-future-skew-seconds") long futureSkewSeconds,
            @ConfigProperty(name = "analytics.consumer.max-retries") int maxRetries,
            @ConfigProperty(name = "analytics.consumer.retry-delay-ms") long retryDelayMs,
            @ConfigProperty(name = "analytics.consumer.persistence-timeout-ms") long persistenceTimeoutMs,
            PersistenceFailureObserver failures) {
        if (maxRetries < 0 || maxRetries > 3 || retryDelayMs < 1 || retryDelayMs > 1000
                || persistenceTimeoutMs < 100 || persistenceTimeoutMs > 10_000) {
            throw new IllegalArgumentException("Invalid analytics consumer bounds");
        }
        this.handler = handler;
        this.deadLetter = deadLetter;
        this.mapper = mapper;
        this.meters = meters;
        this.maxEventBytes = maxEventBytes;
        this.maxSamples = maxSamples;
        this.futureSkew = Duration.ofSeconds(futureSkewSeconds);
        this.maxRetries = maxRetries;
        this.retryDelay = Duration.ofMillis(retryDelayMs);
        this.persistenceTimeout = Duration.ofMillis(persistenceTimeoutMs);
        this.failures = failures;
    }

    public TelemetryBatchAcceptedListener(AdmitAndProjectTelemetryBatchEventHandler handler,
            AnalyticsDeadLetterPort deadLetter, ObjectMapper mapper, MeterRegistry meters,
            int maxEventBytes, int maxSamples, long futureSkewSeconds, int maxRetries,
            long retryDelayMs, long persistenceTimeoutMs) {
        this(handler, deadLetter, mapper, meters, maxEventBytes, maxSamples, futureSkewSeconds,
                maxRetries, retryDelayMs, persistenceTimeoutMs, PersistenceFailureObserver.NOOP);
    }

    @Incoming("analytics-telemetry")
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
            var payload = message.getPayload();
            var batch = TelemetryBatchAcceptedV1Decoder.decode(key, payload, mapper,
                    maxEventBytes, maxSamples, futureSkew, Instant.now());
            var provenance = new Provenance(topic, partition, offset,
                    payload.getBytes(StandardCharsets.UTF_8).length);
            return Uni.createFrom().deferred(() -> handler.handle(batch, provenance))
                    .ifNoItem().after(persistenceTimeout).fail()
                    .onFailure().retry().withBackOff(retryDelay, retryDelay.multipliedBy(4)).atMost(maxRetries)
                    .subscribeAsCompletionStage()
                    .handle((result, failure) -> {
                        if (failure != null) {
                            failures.record(Operation.PIPELINE, failure);
                            return quarantine(key, message, FailureCode.PERSISTENCE_RETRIES_EXHAUSTED,
                                    topic, partition, offset);
                        }
                        if (result.status() == Status.BATCH_CONFLICT) {
                            return quarantine(key, message, FailureCode.BATCH_CONFLICT, topic, partition, offset);
                        }
                        if (result.status() == Status.SEQUENCE_CONFLICT) {
                            return quarantine(key, message, FailureCode.SEQUENCE_CONFLICT, topic, partition, offset);
                        }
                        return message.ack();
                    }).thenCompose(Function.identity());
        } catch (RuntimeException rejected) {
            return quarantine(key, message, FailureCode.INVALID_EVENT, topic, partition, offset);
        }
    }

    private CompletionStage<Void> quarantine(String key, Message<String> message, FailureCode code,
            String topic, int partition, long offset) {
        return deadLetter.quarantine(new FailedRecord(key, message.getPayload(), code, topic, partition, offset))
                .handle((ignored, failure) -> {
                    try {
                        meters.counter("analytics.dlq.publications", "outcome",
                                failure == null ? "success" : "failure").increment();
                    } catch (RuntimeException metricUnavailable) {
                        // Observability must not change publication acknowledgement or redelivery.
                    }
                    return failure == null
                            ? message.ack()
                            : message.nack(new IllegalStateException("Analytics DLQ unavailable"));
                })
                .thenCompose(Function.identity());
    }
}
