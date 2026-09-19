package com.neurovision.rehab.analytics.infrastructure.integration;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AnalyticsKafkaMongoTestResource.class, restrictToAnnotatedClass = true)
class AnalyticsKafkaMongoIT {
    private static final String SOURCE_TOPIC = "telemetry-batch-accepted.v1";
    private static final String DLQ_TOPIC = "analytics-telemetry.v1.dlq";
    private static final String GROUP = "analytics-inbox-v1";

    @Inject ObjectMapper mapper;
    @Inject Instance<ReactiveMongoClient> mongo;
    @Inject @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;
    @Inject @ConfigProperty(name = "quarkus.mongodb.database") String database;
    @Inject @ConfigProperty(name = "analytics.inbox.collection") String collectionName;

    @Test
    void admitsOrdersDeduplicatesAndQuarantinesBeforeCommittingOffsets() throws Exception {
        var inbox = mongo.get().getDatabase(database).getCollection(collectionName);
        assertEquals(0, inbox.countDocuments().await().atMost(Duration.ofSeconds(5)));

        var fixture = (ObjectNode) mapper.readTree(new String(getClass().getResourceAsStream(
                "/fixtures/telemetry/v1/accepted-event-wire.json").readAllBytes(), StandardCharsets.UTF_8));
        var key = fixture.get("therapySessionId").asText() + ":" + fixture.get("streamId").asText();
        var published = new ArrayList<Published>();

        try (var producer = producer()) {
            published.add(send(producer, key, fixture));

            var inOrder = variant(fixture, 4);
            published.add(send(producer, key, inOrder));

            var gap = variant(fixture, 6);
            published.add(send(producer, key, gap));

            var late = variant(fixture, 5);
            published.add(send(producer, key, late));

            ObjectNode duplicateAttempt = fixture.deepCopy();
            duplicateAttempt.put("eventId", UUID.randomUUID().toString());
            duplicateAttempt.put("serverReceivedAt", "2026-09-17T10:00:03Z");
            published.add(send(producer, key, duplicateAttempt));

            ObjectNode batchConflict = fixture.deepCopy();
            batchConflict.put("eventId", UUID.randomUUID().toString());
            ((ObjectNode) batchConflict.withArray("gameplaySamples").get(0)).put("score", 13);
            published.add(send(producer, key, batchConflict));

            ObjectNode sequenceConflict = fixture.deepCopy();
            sequenceConflict.put("eventId", UUID.randomUUID().toString());
            sequenceConflict.put("batchId", UUID.randomUUID().toString());
            published.add(send(producer, key, sequenceConflict));

            ObjectNode invalid = fixture.deepCopy();
            invalid.put("eventId", UUID.randomUUID().toString());
            invalid.put("inventedContext", "must-fail-closed");
            published.add(send(producer, key, invalid));
            producer.flush();
        }

        await(Duration.ofSeconds(20), () -> count(inbox) == 4);
        assertEquals(Map.of(3L, "INITIAL", 4L, "IN_ORDER", 5L, "LATE_ARRIVAL", 6L, "GAP_DETECTED"),
                admissionQualities(inbox));

        var indexes = inbox.listIndexes().collect().asList().await().atMost(Duration.ofSeconds(5));
        assertUniqueIndex(indexes, "uq_analytics_batch_id", new Document("batchId", 1));
        assertUniqueIndex(indexes, "uq_analytics_stream_sequence",
                new Document("therapySessionId", 1).append("streamId", 1).append("sequence", 1));

        var dlq = consumeDlq(3, Duration.ofSeconds(15));
        assertEquals(3, dlq.size());
        assertEquals(Map.of("BATCH_CONFLICT", 1L, "SEQUENCE_CONFLICT", 1L, "INVALID_EVENT", 1L),
                dlq.stream().collect(java.util.stream.Collectors.groupingBy(
                        record -> header(record, "analytics-failure-code"), java.util.stream.Collectors.counting())));
        assertTrue(dlq.stream().allMatch(record -> record.value().contains("\"schemaVersion\":1")));
        assertTrue(dlq.stream().noneMatch(record -> record.value().contains("patient")));

        var last = published.get(published.size() - 1);
        await(Duration.ofSeconds(15), () -> committedOffset(last.partition()) >= last.offset() + 1);
        assertTrue(committedOffset(last.partition()) >= last.offset() + 1);
    }

    private ObjectNode variant(ObjectNode fixture, long sequence) {
        ObjectNode variant = fixture.deepCopy();
        variant.put("eventId", UUID.randomUUID().toString());
        variant.put("batchId", UUID.randomUUID().toString());
        variant.put("sequence", sequence);
        return variant;
    }

    private KafkaProducer<String, String> producer() {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(properties);
    }

    private Published send(KafkaProducer<String, String> producer, String key,
            com.fasterxml.jackson.databind.JsonNode value) throws Exception {
        var metadata = producer.send(new ProducerRecord<>(SOURCE_TOPIC, key, mapper.writeValueAsString(value))).get();
        return new Published(metadata.partition(), metadata.offset());
    }

    private long count(io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> inbox) {
        return inbox.countDocuments().await().atMost(Duration.ofSeconds(2));
    }

    private Map<Long, String> admissionQualities(
            io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> inbox) {
        var result = new HashMap<Long, String>();
        for (var document : inbox.find().collect().asList().await().atMost(Duration.ofSeconds(5))) {
            result.put(document.getLong("sequence"), document.getString("admissionQuality"));
            assertEquals("ADMITTED", document.getString("status"));
            assertFalse(document.containsKey("patientId"));
            assertFalse(document.containsKey("token"));
        }
        return result;
    }

    private void assertUniqueIndex(List<Document> indexes, String name, Document key) {
        assertTrue(indexes.stream().anyMatch(index -> name.equals(index.getString("name"))
                && Boolean.TRUE.equals(index.getBoolean("unique")) && key.equals(index.get("key", Document.class))));
    }

    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> consumeDlq(
            int expected, Duration timeout) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "analytics-dlq-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(DLQ_TOPIC));
            var deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }

    private String header(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, String name) {
        var value = record.headers().lastHeader(name);
        return value == null ? "" : new String(value.value(), StandardCharsets.UTF_8);
    }

    private long committedOffset(int partition) {
        var properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        try (var admin = AdminClient.create(properties)) {
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
            var value = offsets.get(new TopicPartition(SOURCE_TOPIC, partition));
            return value == null ? -1 : value.offset();
        } catch (Exception failure) {
            return -1;
        }
    }

    private void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for isolated Analytics integration state");
    }

    private record Published(int partition, long offset) {}
}
