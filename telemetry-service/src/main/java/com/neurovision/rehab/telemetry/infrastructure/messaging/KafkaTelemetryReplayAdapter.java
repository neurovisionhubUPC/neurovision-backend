package com.neurovision.rehab.telemetry.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryDeadLetterPort.FailureCode;
import com.neurovision.rehab.telemetry.application.ports.out.messaging.TelemetryReplayPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/** Local operator adapter. The fixed route never accepts arbitrary Kafka topic names. */
public final class KafkaTelemetryReplayAdapter implements TelemetryReplayPort {
    public static final String DLQ = "telemetry-batch-accepted.v1.dlq";
    public static final String SOURCE = "telemetry-batch-accepted.v1";
    public static final String AUDIT = "neurovision.dlq-replay-audit.v1";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private final String bootstrapServers;
    private final ObjectMapper mapper = new ObjectMapper();

    public KafkaTelemetryReplayAdapter(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("Kafka bootstrap server required");
        }
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    public Metadata inspect(Position position) {
        return metadata(readExact(position), position);
    }

    @Override
    public ReplayReceipt replay(Position position, String operator, String reasonCode) {
        var original = readExact(position);
        var details = metadata(original, position);
        if (details.failureCode() != FailureCode.PERSISTENCE_RETRIES_EXHAUSTED) {
            throw new IllegalArgumentException("Only exhausted transient failures can be replayed unchanged");
        }
        String replayId = UUID.randomUUID().toString();
        try (var producer = new KafkaProducer<byte[], byte[]>(producerProperties())) {
            audit(producer, replayId, "REQUESTED", position, operator, reasonCode, null);
            try {
                var sent = send(producer, new ProducerRecord<>(SOURCE, original.key(), original.value()));
                try {
                    audit(producer, replayId, "COMPLETED", position, operator, reasonCode,
                            sent.partition() + ":" + sent.offset());
                } catch (RuntimeException incompleteAudit) {
                    throw new IllegalStateException("Replay outcome ambiguous: terminal audit unavailable");
                }
                return new ReplayReceipt(replayId, sent.partition(), sent.offset());
            } catch (RuntimeException failed) {
                if (failed.getMessage() != null && failed.getMessage().startsWith("Replay outcome ambiguous")) {
                    throw failed;
                }
                try {
                    audit(producer, replayId, "FAILED", position, operator, reasonCode, null);
                } catch (RuntimeException auditUnavailable) {
                    throw new IllegalStateException("Replay outcome ambiguous: failure audit unavailable");
                }
                throw new IllegalStateException("Replay publication failed; inspect audit before retrying");
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Replay broker unavailable");
        }
    }

    private ConsumerRecord<byte[], byte[]> readExact(Position position) {
        var partition = new TopicPartition(DLQ, position.partition());
        try (var consumer = new KafkaConsumer<byte[], byte[]>(consumerProperties())) {
            consumer.assign(java.util.List.of(partition));
            consumer.seek(partition, position.offset());
            long deadline = System.nanoTime() + LOOKUP_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(250));
                for (var record : polled.records(partition)) {
                    if (record.offset() != position.offset()) {
                        throw new IllegalArgumentException("Selected DLQ offset is absent");
                    }
                    return record;
                }
            }
            throw new IllegalArgumentException("Selected DLQ offset is unavailable");
        } catch (IllegalArgumentException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("DLQ lookup unavailable");
        }
    }

    private Metadata metadata(ConsumerRecord<byte[], byte[]> record, Position position) {
        try {
            String code = requiredHeader(record, "telemetry-failure-code");
            String source = requiredHeader(record, "telemetry-source-topic");
            int sourcePartition = Integer.parseInt(requiredHeader(record, "telemetry-source-partition"));
            long sourceOffset = Long.parseLong(requiredHeader(record, "telemetry-source-offset"));
            if (!SOURCE.equals(source) || sourcePartition < 0 || sourceOffset < 0 || record.value() == null
                    || record.key() == null || record.key().length == 0) {
                throw new IllegalArgumentException("Invalid Telemetry DLQ provenance");
            }
            return new Metadata(position, FailureCode.valueOf(code), record.value().length,
                    source, sourcePartition, sourceOffset);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Telemetry DLQ metadata");
        }
    }

    private String requiredHeader(ConsumerRecord<byte[], byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) throw new IllegalArgumentException("Missing DLQ header");
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private void audit(KafkaProducer<byte[], byte[]> producer, String id, String status,
            Position position, String operator, String reason, String targetPosition) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("replayId", id);
        fields.put("status", status);
        fields.put("operator", operator);
        fields.put("reasonCode", reason);
        fields.put("sourceTopic", DLQ);
        fields.put("sourcePartition", position.partition());
        fields.put("sourceOffset", position.offset());
        fields.put("targetTopic", SOURCE);
        if ("FAILED".equals(status)) fields.put("deliveryUncertain", true);
        if (targetPosition != null) fields.put("targetPosition", targetPosition);
        fields.put("occurredAt", Instant.now().toString());
        try {
            send(producer, new ProducerRecord<>(AUDIT, id.getBytes(StandardCharsets.UTF_8),
                    mapper.writeValueAsBytes(fields)));
        } catch (Exception failure) {
            throw new IllegalStateException("Replay audit unavailable");
        }
    }

    private org.apache.kafka.clients.producer.RecordMetadata send(KafkaProducer<byte[], byte[]> producer,
            ProducerRecord<byte[], byte[]> record) {
        try {
            return producer.send(record).get(7, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replay publication interrupted");
        } catch (Exception failed) {
            throw new IllegalStateException("Replay publication unavailable");
        }
    }

    private Properties consumerProperties() {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        return properties;
    }

    private Properties producerProperties() {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 1_048_576L);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        return properties;
    }
}
