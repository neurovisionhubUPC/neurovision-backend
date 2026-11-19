package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler.Status;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.infrastructure.observability.MicrometerAnalyticsAdmissionMetricsAdapter;
import com.neurovision.rehab.analytics.interfaces.events.transform.TelemetryBatchAcceptedV1Decoder;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AdmitTelemetryBatchEventHandlerTest {
    @Test
    void characterizesSentAtOnlyReplayAsBatchConflictWithoutReplacingTheOriginal() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final ObjectNode original;
        try (var input = getClass().getResourceAsStream("/fixtures/telemetry/v1/accepted-event-wire.json")) {
            original = (ObjectNode) mapper.readTree(java.util.Objects.requireNonNull(input));
        }
        var replay = original.deepCopy();
        replay.put("sentAt", Instant.parse(original.path("sentAt").asText()).plusMillis(250).toString());
        var restored = replay.deepCopy();
        restored.set("sentAt", original.get("sentAt"));
        assertEquals(original, restored, "The probe must change only sentAt");

        var key = original.path("therapySessionId").asText() + ":" + original.path("streamId").asText();
        var now = Instant.parse("2026-09-17T10:00:05Z");
        var first = TelemetryBatchAcceptedV1Decoder.decode(key, mapper.writeValueAsString(original),
                mapper, 65536, 100, Duration.ofSeconds(60), now);
        var unchanged = TelemetryBatchAcceptedV1Decoder.decode(key, mapper.writeValueAsString(original),
                mapper, 65536, 100, Duration.ofSeconds(60), now);
        var changed = TelemetryBatchAcceptedV1Decoder.decode(key, mapper.writeValueAsString(replay),
                mapper, 65536, 100, Duration.ofSeconds(60), now);
        assertEquals(first.payloadSha256(), unchanged.payloadSha256());
        assertNotEquals(first.payloadSha256(), changed.payloadSha256());

        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        assertEquals(Status.ADMITTED, admit(handler, first).status());
        assertEquals(Status.DUPLICATE, admit(handler, unchanged).status());
        // Characterization of the current policy, not approval of cross-consumer equivalence.
        assertEquals(Status.BATCH_CONFLICT, admit(handler, changed).status());
        assertEquals(1, store.rows.size());
        assertSame(first, store.rows.getFirst());
    }

    @Test
    void observesOnlyTheFailingAdmissionOperationWithoutReplacingItsFailure() {
        var names = java.util.Map.of("prepare", "INBOX_PREPARE", "findByBatchId", "INBOX_FIND_BATCH",
                "findBySequence", "INBOX_FIND_SEQUENCE", "maximumSequence", "INBOX_MAX_SEQUENCE", "insert", "INBOX_INSERT");
        names.forEach((name, operation) -> {
            var failure = new IllegalStateException("synthetic failure");
            var observations = new ArrayList<String>();
            var observer = new com.neurovision.rehab.analytics.application.internal.observability.PersistenceFailureObserver(
                    (stage, error) -> { observations.add(stage.name()); assertSame(failure, error); });
            var store = (TelemetryInboxPort) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{TelemetryInboxPort.class}, (proxy, method, args) -> {
                        if (method.getName().equals(name)) return Uni.createFrom().failure(failure);
                        return method.getName().equals("prepare") || method.getName().equals("insert")
                                ? Uni.createFrom().voidItem() : Uni.createFrom().item(Optional.empty());
                    });
            var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {}, observer);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> admit(handler, batch(UUID.randomUUID(), 0, "same"))));
            assertEquals(List.of(operation), observations);
        });
    }

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();
    private static final TelemetryInboxPort.Provenance SOURCE =
            new TelemetryInboxPort.Provenance("telemetry-batch-accepted.v1", 0, 1, 300);

    @Test
    void classifiesInitialInOrderGapAndLateWithoutDiscardingValidBatches() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        assertEquals(TelemetryAdmissionQuality.INITIAL, admit(handler, batch(UUID.randomUUID(), 3, "a")).quality());
        assertEquals(TelemetryAdmissionQuality.IN_ORDER, admit(handler, batch(UUID.randomUUID(), 4, "b")).quality());
        assertEquals(TelemetryAdmissionQuality.GAP_DETECTED, admit(handler, batch(UUID.randomUUID(), 7, "c")).quality());
        assertEquals(TelemetryAdmissionQuality.LATE_ARRIVAL, admit(handler, batch(UUID.randomUUID(), 5, "d")).quality());
        assertEquals(4, store.rows.size());
    }

    @Test
    void distinguishesDuplicateBatchConflictAndSequenceConflict() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        var id = UUID.randomUUID();
        assertEquals(Status.ADMITTED, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.DUPLICATE, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.BATCH_CONFLICT, admit(handler, batch(id, 1, "changed")).status());
        assertEquals(Status.SEQUENCE_CONFLICT,
                admit(handler, batch(UUID.randomUUID(), 1, "other")).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void rereadsUniqueIndexesAfterConcurrentInsert() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        var candidate = batch(UUID.randomUUID(), 2, "same");
        store.raceWith = candidate;
        assertEquals(Status.DUPLICATE, admit(handler, candidate).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void rereadsSequenceOccupantAfterConcurrentInsert() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        var candidate = batch(UUID.randomUUID(), 2, "candidate");
        store.raceWith = batch(UUID.randomUUID(), 2, "occupant");
        assertEquals(Status.SEQUENCE_CONFLICT, admit(handler, candidate).status());
        assertEquals(1, store.rows.size());
    }

    @Test
    void treatsAnySequenceBelowMaximumLongAsLateWithoutOverflow() {
        var store = new MemoryInbox();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {});
        assertEquals(TelemetryAdmissionQuality.INITIAL,
                admit(handler, batch(UUID.randomUUID(), Long.MAX_VALUE, "maximum")).quality());
        assertEquals(TelemetryAdmissionQuality.LATE_ARRIVAL,
                admit(handler, batch(UUID.randomUUID(), 0, "late")).quality());
    }

    @Test
    void recordsEveryCompletedOutcomeWithOnlyFixedLabels() {
        var store = new MemoryInbox();
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            var id = UUID.randomUUID();
            assertEquals(Status.ADMITTED, admit(handler, batch(id, 1, "same")).status());
            assertEquals(Status.DUPLICATE, admit(handler, batch(id, 1, "same")).status());
            assertEquals(Status.BATCH_CONFLICT, admit(handler, batch(id, 1, "changed")).status());
            assertEquals(Status.SEQUENCE_CONFLICT, admit(handler, batch(UUID.randomUUID(), 1, "other")).status());
            assertEquals(1, store.rows.size());
            for (String outcome : Set.of("admitted", "duplicate", "batch_conflict", "sequence_conflict")) {
                assertEquals(1.0, count(meters, outcome));
            }
            assertEquals(4, meters.getMeters().size());
            assertEquals(Set.of("admitted", "duplicate", "batch_conflict", "sequence_conflict"),
                    meters.getMeters().stream().map(meter -> meter.getId().getTag("outcome")).collect(Collectors.toSet()));
            meters.getMeters().forEach(meter -> {
                assertEquals("analytics.admissions", meter.getId().getName());
                assertEquals(Meter.Type.COUNTER, meter.getId().getType());
                assertEquals(1, meter.getId().getTags().size());
                assertEquals("outcome", meter.getId().getTags().getFirst().getKey());
            });
        }
    }

    @Test
    void recordsOnlyAfterInsertionCompletion() throws Exception {
        var store = new MemoryInbox();
        store.insertCompletion = new CompletableFuture<>();
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            var pending = handler.admit(batch(UUID.randomUUID(), 1, "same"), SOURCE);
            assertTrue(meters.getMeters().isEmpty());
            var completed = pending.subscribeAsCompletionStage();
            assertTrue(meters.getMeters().isEmpty());
            store.insertCompletion.complete(null);
            assertEquals(Status.ADMITTED, completed.toCompletableFuture().get(1, TimeUnit.SECONDS).status());
            assertEquals(1.0, count(meters, "admitted"));
            store.insertCompletion.complete(null);
            assertEquals(1.0, count(meters, "admitted"));
        }
    }

    @Test
    void failedAdmissionHasNoOutcomeAndPreservesFailure() {
        var store = new MemoryInbox();
        var unavailable = new IllegalStateException("Synthetic inbox unavailable");
        store.prepareFailure = unavailable;
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            assertSame(unavailable, assertThrows(IllegalStateException.class,
                    () -> admit(handler, batch(UUID.randomUUID(), 1, "same"))));
            assertTrue(meters.getMeters().isEmpty());
            assertTrue(store.rows.isEmpty());
        }
    }

    @Test
    void recoveredRaceCountsOnlyItsFinalOutcome() {
        var store = new MemoryInbox();
        var candidate = batch(UUID.randomUUID(), 1, "same");
        store.raceWith = candidate;
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            assertEquals(Status.DUPLICATE, admit(handler, candidate).status());
            assertEquals(1.0, count(meters, "duplicate"));
            assertEquals(0.0, count(meters, "admitted"));
            assertEquals(1, store.rows.size());
        }
    }

    @Test
    void observationFailuresCannotChangeAdmissionOutcomes() {
        var store = new MemoryInbox();
        var observed = new AtomicInteger();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {
            observed.incrementAndGet();
            throw new IllegalStateException("Synthetic metric unavailable");
        });
        var id = UUID.randomUUID();
        assertEquals(Status.ADMITTED, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.DUPLICATE, admit(handler, batch(id, 1, "same")).status());
        assertEquals(Status.BATCH_CONFLICT, admit(handler, batch(id, 1, "changed")).status());
        assertEquals(Status.SEQUENCE_CONFLICT, admit(handler, batch(UUID.randomUUID(), 1, "other")).status());
        assertEquals(4, observed.get());
        assertEquals(1, store.rows.size());
    }

    @Test
    void registryFailureCannotChangeTheAdmissionResult() {
        var store = new MemoryInbox();
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            meters.config().meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) {
                    throw new IllegalStateException("Synthetic registry unavailable");
                }
            });
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            assertEquals(Status.ADMITTED, admit(handler, batch(UUID.randomUUID(), 1, "same")).status());
            assertEquals(1, store.rows.size());
            assertTrue(meters.getMeters().isEmpty());
        }
    }

    @Test
    void projectionRetryCountsAdmissionAttemptsNotUniqueEvents() {
        var store = new MemoryInbox();
        var unavailable = new IllegalStateException("Synthetic projection unavailable");
        var projectionCalls = new AtomicInteger();
        try (var fixture = new MeterFixture()) {
            var meters = fixture.meters;
            var handler = new AdmitTelemetryBatchEventHandler(store,
                    new MicrometerAnalyticsAdmissionMetricsAdapter(meters));
            var orchestrator = new AdmitAndProjectTelemetryBatchEventHandler(handler, identity -> {
                projectionCalls.incrementAndGet();
                assertEquals(1.0, count(meters, "admitted"));
                return Uni.createFrom().failure(unavailable);
            });
            var candidate = batch(UUID.randomUUID(), 1, "same");
            assertSame(unavailable, assertThrows(IllegalStateException.class,
                    () -> orchestrator.handle(candidate, SOURCE).onFailure().retry().atMost(1).await().indefinitely()));
            assertEquals(1.0, count(meters, "admitted"));
            assertEquals(1.0, count(meters, "duplicate"));
            assertEquals(2, projectionCalls.get());
            assertEquals(1, store.rows.size());
        }
    }

    @Test
    void failedPendingInsertPreservesFailureWithoutObservation() {
        var store = new MemoryInbox();
        store.insertCompletion = new CompletableFuture<>();
        var observed = new AtomicInteger();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> observed.incrementAndGet());
        var pending = handler.admit(batch(UUID.randomUUID(), 1, "same"), SOURCE).subscribeAsCompletionStage();
        assertEquals(0, observed.get());
        var unavailable = new IllegalStateException("Synthetic pending insert unavailable");
        store.insertCompletion.completeExceptionally(unavailable);
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> pending.toCompletableFuture().join());
        assertSame(unavailable, failure.getCause());
        assertEquals(0, observed.get());
    }

    @Test
    void throwingObserverCannotPreventProjectionOrReplaceProjectionFailure() {
        var store = new MemoryInbox();
        var observed = new AtomicInteger();
        var handler = new AdmitTelemetryBatchEventHandler(store, outcome -> {
            observed.incrementAndGet();
            throw new IllegalStateException("Synthetic metrics unavailable");
        });
        var projectionCalls = new AtomicInteger();
        var unavailable = new IllegalStateException("Synthetic projection unavailable");
        var orchestrator = new AdmitAndProjectTelemetryBatchEventHandler(handler, identity -> {
            assertEquals(projectionCalls.get() + 1, observed.get());
            projectionCalls.incrementAndGet();
            return Uni.createFrom().failure(unavailable);
        });
        var candidate = batch(UUID.randomUUID(), 1, "same");
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> orchestrator.handle(candidate, SOURCE).onFailure().retry().atMost(1).await().indefinitely()));
        assertEquals(2, observed.get());
        assertEquals(2, projectionCalls.get());
        assertEquals(1, store.rows.size());
    }

    private double count(SimpleMeterRegistry meters, String outcome) {
        var counter = meters.find("analytics.admissions").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private AdmitTelemetryBatchEventHandler.Result admit(
            AdmitTelemetryBatchEventHandler handler, AcceptedTelemetryBatch batch) {
        return handler.admit(batch, SOURCE).await().indefinitely();
    }

    private AcceptedTelemetryBatch batch(UUID id, long sequence, String hash) {
        var now = Instant.parse("2026-09-17T10:00:00Z");
        return new AcceptedTelemetryBatch(1, UUID.randomUUID(), id, SESSION, STREAM, sequence,
                now, now.plusSeconds(1), now.plusSeconds(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), hash);
    }

    private static final class MeterFixture implements AutoCloseable {
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

        @Override public void close() { meters.close(); }
    }
    private static final class MemoryInbox implements TelemetryInboxPort {
        private final List<AcceptedTelemetryBatch> rows = new ArrayList<>();
        private AcceptedTelemetryBatch raceWith;
        private CompletableFuture<Void> insertCompletion;
        private RuntimeException prepareFailure;

        @Override public Uni<Void> prepare() {
            return prepareFailure == null ? Uni.createFrom().voidItem() : Uni.createFrom().failure(prepareFailure);
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
            if (raceWith != null) {
                rows.add(raceWith); raceWith = null;
                return Uni.createFrom().failure(new ConcurrentInsertException());
            }
            rows.add(batch);
            return insertCompletion == null ? Uni.createFrom().voidItem()
                    : Uni.createFrom().completionStage(insertCompletion);
        }
    }
}
