package com.neurovision.rehab.analytics.infrastructure.messaging;

import com.neurovision.rehab.analytics.application.ports.out.messaging.AnalyticsDeadLetterPort;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class KafkaAnalyticsDeadLetterPublisher implements AnalyticsDeadLetterPort {
    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final long acknowledgementTimeoutMs;

    @Inject
    public KafkaAnalyticsDeadLetterPublisher(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers,
            @ConfigProperty(name = "analytics.dlq.topic") String topic,
            @ConfigProperty(name = "analytics.dlq.ack-timeout-ms") long acknowledgementTimeoutMs) {
        if (topic == null || topic.isBlank() || acknowledgementTimeoutMs < 100
                || acknowledgementTimeoutMs > 10_000) {
            throw new IllegalArgumentException("Invalid analytics DLQ bounds");
        }
        this.topic = topic;
        this.acknowledgementTimeoutMs = acknowledgementTimeoutMs;
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 1_048_576L);
        producer = new KafkaProducer<>(properties);
    }

    @Override
    public CompletionStage<Void> quarantine(FailedRecord failed) {
        var record = new ProducerRecord<>(topic, failed.key(), failed.payload());
        header(record, "analytics-failure-code", failed.code().name());
        header(record, "analytics-source-topic", failed.sourceTopic());
        header(record, "analytics-source-partition", Integer.toString(failed.sourcePartition()));
        header(record, "analytics-source-offset", Long.toString(failed.sourceOffset()));
        var acknowledged = new CompletableFuture<Void>();
        try {
            producer.send(record, (metadata, error) -> {
                if (error == null) acknowledged.complete(null);
                else acknowledged.completeExceptionally(new IllegalStateException("Analytics DLQ unavailable"));
            });
        } catch (RuntimeException unavailable) {
            acknowledged.completeExceptionally(new IllegalStateException("Analytics DLQ unavailable"));
        }
        return acknowledged.orTimeout(acknowledgementTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    @PreDestroy
    void close() {
        producer.close(Duration.ofSeconds(2));
    }
}
