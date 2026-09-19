package com.neurovision.rehab.analytics.infrastructure.integration;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StreamSummaryProjectionPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoHeatmapProjectionQueryAdapter;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoStreamSummaryProjectionAdapter;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoTelemetryInboxAdapter;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.bson.conversions.Bson;
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
    @Inject @ConfigProperty(name = "analytics.summary.collection") String summaryCollectionName;
    @Inject MongoTelemetryInboxAdapter inboxAdapter;
    @Inject MongoStreamSummaryProjectionAdapter summaryAdapter;
    @Inject MongoHeatmapProjectionQueryAdapter heatmapQueryAdapter;

    @Test
    void admitsProjectsRecoversAndConvergesBeforeCommittingOffsets() throws Exception {
        var inbox = mongo.get().getDatabase(database).getCollection(collectionName);
        var summaries = mongo.get().getDatabase(database).getCollection(summaryCollectionName);
        assertEquals(0, inbox.countDocuments().await().atMost(Duration.ofSeconds(5)));
        assertEquals(0, summaries.countDocuments().await().atMost(Duration.ofSeconds(5)));

        var fixture = (ObjectNode) mapper.readTree(new String(getClass().getResourceAsStream(
                "/fixtures/telemetry/v1/accepted-event-wire.json").readAllBytes(), StandardCharsets.UTF_8));
        var key = fixture.get("therapySessionId").asText() + ":" + fixture.get("streamId").asText();
        var identity = new TelemetryStreamIdentity(
                UUID.fromString(fixture.get("therapySessionId").asText()),
                UUID.fromString(fixture.get("streamId").asText()));
        var published = new ArrayList<Published>();

        try (var producer = producer()) {
            published.add(send(producer, key, fixture));

            var inOrder = variant(fixture, 4);
            inOrder.remove("traceId");
            published.add(send(producer, key, inOrder));

            var gap = variant(fixture, 6);
            published.add(send(producer, key, gap));
            producer.flush();
        }

        await(Duration.ofSeconds(20), () -> count(inbox) == 3);
        await(Duration.ofSeconds(20), () -> admittedBatchCount(summaries, identity) == 3);

        var authorizedQuery = new QueryHeatmapProjectionService(
                (sessionId, accessToken) -> HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED,
                heatmapQueryAdapter);
        var initial = authorizedQuery.query(identity.therapySessionId(), identity.streamId(), "synthetic-token")
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(QueryHeatmapProjectionService.Status.OK, initial.status());
        assertHeatmapProjection(initial.projection(), identity, 3, 3, 0, List.of(
                new HeatmapProjection.SequenceRange(5, 5)));

        try (var producer = producer()) {
            var late = variant(fixture, 5);
            published.add(send(producer, key, late));
            producer.flush();
        }

        await(Duration.ofSeconds(20), () -> count(inbox) == 4);
        await(Duration.ofSeconds(20), () -> admittedBatchCount(summaries, identity) == 4);
        var advanced = authorizedQuery.query(identity.therapySessionId(), identity.streamId(), "synthetic-token")
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(QueryHeatmapProjectionService.Status.OK, advanced.status());
        assertHeatmapProjection(advanced.projection(), identity, 4, 4, 1, List.of());
        assertTrue(advanced.projection().revision() > initial.projection().revision());
        assertFalse(advanced.projection().cellCounts().equals(initial.projection().cellCounts()));

        assertEquals(Map.of(3L, "INITIAL", 4L, "IN_ORDER", 5L, "LATE_ARRIVAL", 6L, "GAP_DETECTED"),
                admissionQualities(inbox));

        var inboxIndexes = inbox.listIndexes().collect().asList().await().atMost(Duration.ofSeconds(5));
        assertUniqueIndex(inboxIndexes, "uq_analytics_batch_id", new Document("batchId", 1));
        assertUniqueIndex(inboxIndexes, "uq_analytics_stream_sequence",
                new Document("therapySessionId", 1).append("streamId", 1).append("sequence", 1));
        var summaryIndexes = summaries.listIndexes().collect().asList().await().atMost(Duration.ofSeconds(5));
        assertUniqueIndex(summaryIndexes, "uq_analytics_stream_calculation",
                new Document("therapySessionId", 1).append("streamId", 1)
                        .append("calculationVersion", 1));

        var reconstructed = inboxAdapter.loadCompleteStream(identity).await().atMost(Duration.ofSeconds(5));
        assertEquals(4, reconstructed.admittedBatchCount());
        assertEquals(List.of(3L, 4L, 5L, 6L), reconstructed.batches().stream()
                .map(batch -> batch.batch().sequence()).toList());
        var correlationId = UUID.fromString(fixture.get("correlationId").asText());
        assertTrue(reconstructed.batches().stream()
                .allMatch(batch -> correlationId.equals(batch.batch().correlationId())));
        assertEquals("synthetic-trace", reconstructed.batches().get(0).batch().traceId());
        assertNull(reconstructed.batches().get(1).batch().traceId());
        assertEquals("synthetic-trace", reconstructed.batches().get(2).batch().traceId());

        assertBaselineProjection(summary(summaries, identity));

        summaries.deleteOne(summaryIdentity(identity)).await().atMost(Duration.ofSeconds(5));
        assertEquals(0, summaries.countDocuments(summaryIdentity(identity))
                .await().atMost(Duration.ofSeconds(5)));

        try (var producer = producer()) {
            ObjectNode duplicateAttempt = fixture.deepCopy();
            duplicateAttempt.put("eventId", UUID.randomUUID().toString());
            duplicateAttempt.put("serverReceivedAt", "2026-09-17T10:00:03Z");
            published.add(send(producer, key, duplicateAttempt));
            producer.flush();
        }

        await(Duration.ofSeconds(20), () -> admittedBatchCount(summaries, identity) == 4);
        assertEquals(4, count(inbox));
        assertEquals(1, summaries.countDocuments(summaryIdentity(identity))
                .await().atMost(Duration.ofSeconds(5)));
        assertBaselineProjection(summary(summaries, identity));

        verifyLegacyInboxCompatibility(inbox, summaries, fixture, published);

        try (var producer = producer()) {
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

        verifyAtomicProjectionConvergence(summaries);
        verifyMalformedHistoricalRowFailsClosed(inbox);
    }

    private void verifyLegacyInboxCompatibility(
            io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> inbox,
            io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> summaries,
            ObjectNode fixture, List<Published> published) throws Exception {
        var identity = new TelemetryStreamIdentity(UUID.randomUUID(), UUID.randomUUID());
        var legacyBatchId = UUID.randomUUID();
        var source = inbox.find().collect().first().await().atMost(Duration.ofSeconds(5));
        var legacy = new Document(source)
                .append("_id", legacyBatchId.toString())
                .append("batchId", legacyBatchId.toString())
                .append("attemptEventId", UUID.randomUUID().toString())
                .append("therapySessionId", identity.therapySessionId().toString())
                .append("streamId", identity.streamId().toString())
                .append("sequence", 0L)
                .append("admissionQuality", "INITIAL");
        legacy.remove("inboxSchemaVersion");
        legacy.remove("correlationId");
        inbox.insertOne(legacy).await().atMost(Duration.ofSeconds(5));

        var current = fixture.deepCopy();
        current.put("eventId", UUID.randomUUID().toString());
        var currentBatchId = UUID.randomUUID();
        current.put("batchId", currentBatchId.toString());
        current.put("therapySessionId", identity.therapySessionId().toString());
        current.put("streamId", identity.streamId().toString());
        current.put("sequence", 1L);
        current.put("correlationId", UUID.randomUUID().toString());
        var key = identity.therapySessionId() + ":" + identity.streamId();

        try (var producer = producer()) {
            published.add(send(producer, key, current));
            producer.flush();
        }

        await(Duration.ofSeconds(20), () -> admittedBatchCount(summaries, identity) == 2);
        var reconstructed = inboxAdapter.loadCompleteStream(identity).await().atMost(Duration.ofSeconds(5));
        assertEquals(2, reconstructed.admittedBatchCount());
        assertEquals(List.of(0L, 1L), reconstructed.batches().stream()
                .map(batch -> batch.batch().sequence()).toList());
        assertEquals(UUID.nameUUIDFromBytes(("legacy-inbox:" + legacyBatchId)
                        .getBytes(StandardCharsets.UTF_8)),
                reconstructed.batches().get(0).batch().correlationId());

        var legacyPersisted = inbox.find(eq("batchId", legacyBatchId.toString())).collect().first()
                .await().atMost(Duration.ofSeconds(5));
        assertFalse(legacyPersisted.containsKey("inboxSchemaVersion"));
        assertFalse(legacyPersisted.containsKey("correlationId"));
        var currentPersisted = inbox.find(eq("batchId", currentBatchId.toString())).collect().first()
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(2, currentPersisted.getInteger("inboxSchemaVersion"));
        var projected = summary(summaries, identity);
        assertEquals(2L, projected.getLong("admittedBatchCount"));
        assertEquals(2L, projected.get("summary", Document.class)
                .get("sampleCounts", Document.class).getLong("gaze"));
        assertTrue(consumeDlq(1, Duration.ofSeconds(2)).isEmpty());

        var last = published.get(published.size() - 1);
        await(Duration.ofSeconds(15), () -> committedOffset(last.partition()) >= last.offset() + 1);
    }

    private void assertBaselineProjection(Document projected) {
        assertEquals(4L, projected.getLong("admittedBatchCount"));
        var baseline = projected.get("summary", Document.class);
        assertEquals("baseline-v1", baseline.getString("profile"));
        assertEquals(6L, baseline.getLong("highSequence"));
        assertEquals(1L, baseline.getLong("lateArrivalCount"));
        assertTrue(baseline.getList("missingSequenceRanges", Document.class).isEmpty());
        assertEquals(4L, baseline.get("sampleCounts", Document.class).getLong("gaze"));
        assertEquals(4L, baseline.get("sampleCounts", Document.class).getLong("gameplay"));
        assertFalse(projected.toJson().contains("patientId"));
        assertFalse(projected.toJson().contains("doctor"));
    }

    private void assertHeatmapProjection(
            HeatmapProjection projection,
            TelemetryStreamIdentity identity, long revision, long gazeSampleCount,
            long lateArrivalCount,
            List<HeatmapProjection.SequenceRange> missingRanges) {
        assertEquals(identity.therapySessionId(), projection.identity().therapySessionId());
        assertEquals(identity.streamId(), projection.identity().streamId());
        assertEquals("baseline-v1", projection.identity().calculationVersion());
        assertEquals(revision, projection.revision());
        assertEquals(1, projection.sourceSchemaVersion());
        assertEquals(HeatmapProjection.CoordinateSpace.NORMALIZED_ZERO_TO_ONE, projection.coordinateSpace());
        assertEquals(HeatmapProjection.CellWeight.RAW_GAZE_SAMPLE_COUNT, projection.cellWeight());
        assertFalse(projection.confidenceFiltered());
        assertEquals(10, projection.rows());
        assertEquals(10, projection.columns());
        assertEquals(100, projection.cellCounts().size());
        var expectedCells = new ArrayList<Long>(java.util.Collections.nCopies(100, 0L));
        expectedCells.set(82, gazeSampleCount);
        assertEquals(expectedCells, projection.cellCounts());
        assertEquals(3, projection.minimumSequence());
        assertEquals(6, projection.maximumSequence());
        assertEquals(6, projection.highSequence());
        assertEquals(missingRanges, projection.missingSequenceRanges());
        assertEquals(lateArrivalCount, projection.lateArrivalCount());
        assertTrue(projection.unknownSessionBoundary());
        assertEquals(gazeSampleCount, projection.gazeSampleCount());
        assertEquals(Instant.parse("2026-09-17T10:00:00Z"), projection.observedTimeRange().orElseThrow().minimum());
        assertEquals(Instant.parse("2026-09-17T10:00:00.001Z"),
                projection.observedTimeRange().orElseThrow().maximum());
    }

    private void verifyAtomicProjectionConvergence(
            io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> summaries) {
        var identity = new TelemetryStreamIdentity(UUID.randomUUID(), UUID.randomUUID());
        var older = projection(identity, 1);
        var newer = projection(identity, 2);

        var concurrent = Uni.combine().all().unis(
                        summaryAdapter.storeIfNewer(older), summaryAdapter.storeIfNewer(newer))
                .asTuple().await().atMost(Duration.ofSeconds(5));

        assertTrue(concurrent.getItem1() == StreamSummaryProjectionPort.WriteResult.INSERTED
                || concurrent.getItem2() == StreamSummaryProjectionPort.WriteResult.INSERTED);
        assertEquals(2L, summary(summaries, identity).getLong("admittedBatchCount"));
        assertEquals(StreamSummaryProjectionPort.WriteResult.NOT_ADVANCED,
                summaryAdapter.storeIfNewer(older).await().atMost(Duration.ofSeconds(5)));
        assertEquals(StreamSummaryProjectionPort.WriteResult.NOT_ADVANCED,
                summaryAdapter.storeIfNewer(newer).await().atMost(Duration.ofSeconds(5)));
        var persisted = summary(summaries, identity);
        assertEquals(2L, persisted.getLong("admittedBatchCount"));
        assertEquals(2L, persisted.get("summary", Document.class)
                .get("sampleCounts", Document.class).getLong("gaze"));
    }

    private void verifyMalformedHistoricalRowFailsClosed(
            io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> inbox) {
        var source = inbox.find().collect().first().await().atMost(Duration.ofSeconds(5));
        var malformedIdentity = new TelemetryStreamIdentity(UUID.randomUUID(), UUID.randomUUID());
        var malformedBatch = UUID.randomUUID();
        var malformed = new Document(source)
                .append("_id", malformedBatch.toString())
                .append("batchId", malformedBatch.toString())
                .append("attemptEventId", UUID.randomUUID().toString())
                .append("therapySessionId", malformedIdentity.therapySessionId().toString())
                .append("streamId", malformedIdentity.streamId().toString())
                .append("sequence", 0L);
        malformed.remove("correlationId");
        inbox.insertOne(malformed).await().atMost(Duration.ofSeconds(5));

        assertThrows(IllegalArgumentException.class,
                () -> inboxAdapter.loadCompleteStream(malformedIdentity).await().atMost(Duration.ofSeconds(5)));

        inbox.deleteOne(eq("_id", malformedBatch.toString())).await().atMost(Duration.ofSeconds(5));
    }

    private StreamSummaryProjection projection(TelemetryStreamIdentity identity, int batchCount) {
        var batches = new ArrayList<AdmittedTelemetryBatch>();
        for (int sequence = 0; sequence < batchCount; sequence++) {
            var capturedAt = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
            var accepted = new AcceptedTelemetryBatch(
                    1, UUID.randomUUID(), UUID.randomUUID(), identity.therapySessionId(), identity.streamId(),
                    sequence, capturedAt, capturedAt.plusMillis(10), capturedAt.plusMillis(20),
                    UUID.randomUUID(), sequence == 0 ? "synthetic-trace" : null,
                    List.of(new AcceptedTelemetryBatch.GazeSample(0, .2 + sequence * .1, .3, .9)),
                    List.of(), "sha256-" + UUID.randomUUID());
            batches.add(new AdmittedTelemetryBatch(accepted,
                    sequence == 0 ? TelemetryAdmissionQuality.INITIAL : TelemetryAdmissionQuality.IN_ORDER));
        }
        var summary = new BaselineTelemetryMetricsCalculator().calculate(batches);
        return new StreamSummaryProjection(AnalyticsProjectionIdentity.from(summary), batchCount, summary);
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

    private Document summary(io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> summaries,
            TelemetryStreamIdentity identity) {
        return summaries.find(summaryIdentity(identity)).collect().first()
                .await().atMost(Duration.ofSeconds(5));
    }

    private Bson summaryIdentity(TelemetryStreamIdentity identity) {
        return new Document("therapySessionId", identity.therapySessionId().toString())
                .append("streamId", identity.streamId().toString())
                .append("calculationVersion", "baseline-v1");
    }

    private long admittedBatchCount(io.quarkus.mongodb.reactive.ReactiveMongoCollection<Document> summaries,
            TelemetryStreamIdentity identity) {
        var document = summary(summaries, identity);
        return document == null ? -1 : document.getLong("admittedBatchCount");
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
