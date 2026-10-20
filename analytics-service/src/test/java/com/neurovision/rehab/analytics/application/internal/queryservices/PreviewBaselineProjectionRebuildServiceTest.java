package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewBaselineProjectionRebuildServiceTest {
    private final UUID session = UUID.randomUUID();
    private final UUID stream = UUID.randomUUID();
    private final BaselineTelemetryMetricsCalculator calculator = new BaselineTelemetryMetricsCalculator();
    private final AtomicLong clock = new AtomicLong();

    @Test void internalContinuationKeepsPrivateEvidenceRedactedAndDoesNotReduceWriteErrors() {
        var fake = new Fake(List.of(row(0)), List.of());
        var preview = new PreviewBaselineProjectionRebuildService(fake, calculator::calculate, clock::get);
        var failure = new IllegalStateException("synthetic-write-failure");
        var thrown = assertThrows(IllegalStateException.class, () -> preview.withPreparedProjection(
                session, stream, "baseline-v1", prepared -> {
                    assertEquals("projection_missing", prepared.outcome().status().code());
                    assertNull(prepared.expected());
                    assertEquals(1, prepared.candidate().admittedBatchCount());
                    assertEquals("PreparedProjection[redacted]", prepared.toString());
                    assertEquals(Duration.ofSeconds(2), prepared.remainingWriteTimeout());
                    return Uni.createFrom().failure(failure);
                }).await().atMost(Duration.ofSeconds(3)));
        assertSame(failure, thrown);
        assertEquals(4, fake.calls.size());
    }

    @Test void stableMissingProjectionUsesFourBoundedReadsAndNoWrites() {
        var fake = new Fake(List.of(row(0)), List.of());
        var result = run(fake);
        assertEquals("projection_missing", result.status().code());
        assertEquals("optimistic_repeated_read", result.consistencyMode());
        assertEquals("baseline-v1", result.calculationVersion());
        assertEquals(1L, result.sourceBatchCount());
        assertEquals(List.of("P", "S", "S", "P"), fake.calls);
        assertFalse(result.toString().contains(session.toString()));
        assertFalse(result.toString().contains("sha256"));
    }

    @Test void rejectsInvalidInputsBeforeReading() {
        var fake = new Fake(List.of(), List.of());
        var service = new PreviewBaselineProjectionRebuildService(fake);
        for (String version : Arrays.asList(null, "", " ")) {
            assertEquals("invalid_request", service.preview(session, stream, version).await().indefinitely().status().code());
        }
        assertEquals("invalid_request", service.preview(null, stream, "baseline-v1").await().indefinitely().status().code());
        assertEquals("invalid_request", service.preview(session, null, "baseline-v1").await().indefinitely().status().code());
        for (String version : List.of("baseline-v2", "BASELINE-V1", " baseline-v1")) {
            assertEquals("unsupported_version", service.preview(session, stream, version).await().indefinitely().status().code());
        }
        assertTrue(fake.calls.isEmpty());
    }

    @Test void emptySourceNeverInvokesCalculator() {
        var fake = new Fake(List.of(), List.of());
        var service = new PreviewBaselineProjectionRebuildService(fake, ignored -> fail("Unexpected calculation"), clock::get);
        var result = service.preview(session, stream, "baseline-v1").await().indefinitely();
        assertEquals("no_source", result.status().code());
        assertEquals(0L, result.sourceBatchCount());
        assertNull(result.projectionRevision());
        assertEquals(List.of("P", "S", "S", "P"), fake.calls);
    }

    @Test void stableClassificationMatrixIncludesFullSummaryMismatch() {
        var rows = List.of(row(0), row(2));
        var summary = summary(rows);
        for (long revision : List.of(1L, 2L, 3L)) {
            var result = run(new Fake(rows, projection(summary, revision)));
            assertEquals(revision == 1 ? "projection_stale" : revision == 2 ? "consistent" : "source_projection_inconsistent",
                    result.status().code());
            assertEquals(revision, result.projectionRevision());
        }
        var different = summary(List.of(row(0)));
        assertEquals("same_revision_mismatch", run(new Fake(rows, projection(different, 2))).status().code());
        assertEquals("source_projection_inconsistent", run(new Fake(List.of(), projection(summary, 2))).status().code());
    }

    @Test void sourceOrderingGapsLegacyAndPersistedLateQualityArePreserved() {
        var first = row(0);
        var later = row(5);
        later = new SourceRow(new AdmittedTelemetryBatch(later.admitted().batch(), TelemetryAdmissionQuality.LATE_ARRIVAL),
                null, "ADMITTED", 128);
        var rows = List.of(later, first);
        var fake = new Fake(rows, projection(summary(rows), 2));
        fake.source1 = List.of(first, later);
        assertEquals("consistent", run(fake).status().code());
        assertEquals(1, summary(rows).lateArrivalCount());
    }

    @ParameterizedTest @ValueSource(strings = {"quality", "content", "hash", "schema", "status", "identity"})
    void changesWithSameCountAndSequenceAreNotHidden(String kind) {
        var original = row(0);
        var batch = original.admitted().batch();
        var changedBatch = new AcceptedTelemetryBatch(batch.schemaVersion(), batch.eventId(), batch.batchId(), session, stream,
                batch.sequence(), batch.capturedAt(), batch.sentAt(), batch.serverReceivedAt(), batch.correlationId(),
                "identity".equals(kind) ? "different-trace" : batch.traceId(),
                "content".equals(kind) ? List.of(new AcceptedTelemetryBatch.GazeSample(0, .7, .3, .9)) : batch.gazeSamples(),
                batch.gameplaySamples(), "hash".equals(kind) ? "different-hash" : batch.payloadSha256());
        var changed = new SourceRow(new AdmittedTelemetryBatch(changedBatch,
                "quality".equals(kind) ? TelemetryAdmissionQuality.LATE_ARRIVAL : original.admitted().admissionQuality()),
                "schema".equals(kind) ? null : 2, "status".equals(kind) ? "PENDING" : "ADMITTED", 128);
        var fake = new Fake(List.of(original), List.of());
        fake.source1 = List.of(changed);
        assertEquals("status".equals(kind) ? "source_invalid" : "source_changed", run(fake).status().code());
    }

    @Test void sourceChangesTakePriorityOverProjectionChanges() {
        var first = row(0);
        var fake = new Fake(List.of(first), List.of());
        fake.source1 = List.of(row(1));
        fake.projection1 = projection(summary(List.of(first)), 1);
        assertEquals("source_changed", run(fake).status().code());
        assertEquals(4, fake.calls.size());
    }

    @Test void projectionPresenceRevisionAndSameRevisionContentChangesAreDetected() {
        var rows = List.of(row(0));
        var existing = projection(summary(rows), 1);
        for (var after : List.of(existing, projection(summary(rows), 2), projection(summary(List.of(row(3))), 1))) {
            var fake = new Fake(rows, after == existing ? List.of() : existing);
            fake.projection1 = after;
            assertEquals("projection_changed", run(fake).status().code());
        }
        var removed = new Fake(rows, existing);
        removed.projection1 = List.of();
        assertEquals("projection_changed", run(removed).status().code());
    }

    @Test void rejectsDuplicateAndWrongIdentitySourceWithoutCalculating() {
        var first = row(0);
        for (var rows : List.of(List.of(first, first), List.of(first, row(0)))) {
            assertEquals("source_invalid", run(new Fake(rows, List.of())).status().code());
        }
        var b = first.admitted().batch();
        var wrong = new AcceptedTelemetryBatch(1, b.eventId(), b.batchId(), UUID.randomUUID(), stream, 0,
                b.capturedAt(), b.sentAt(), b.serverReceivedAt(), b.correlationId(), null, b.gazeSamples(), List.of(), b.payloadSha256());
        assertEquals("source_invalid", run(new Fake(List.of(new SourceRow(new AdmittedTelemetryBatch(wrong,
                TelemetryAdmissionQuality.IN_ORDER), 2, "ADMITTED", 128)), List.of())).status().code());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 3})
    void unsupportedInboxSchemaIsInvalid(int schema) {
        var row = row(0);
        assertEquals("source_invalid", run(new Fake(List.of(new SourceRow(row.admitted(), schema, "ADMITTED", 128)), List.of()))
                .status().code());
    }

    @Test void malformedSourceAndProjectionAreSafeFailures() {
        var row = row(0);
        for (var invalid : Arrays.asList(null, new SourceRow(null, 2, "ADMITTED", 128),
                new SourceRow(row.admitted(), 2, "ADMITTED", 0))) {
            assertEquals("source_invalid", run(new Fake(Arrays.asList(invalid), List.of())).status().code());
        }
        var validProjection = projection(summary(List.of(row)), 1).getFirst();
        for (var projections : List.of(Arrays.<ProjectionRow>asList((ProjectionRow) null),
                List.of(new ProjectionRow(null, 1)), List.of(validProjection, validProjection))) {
            assertEquals("projection_invalid", run(new Fake(List.of(row), projections)).status().code());
        }
    }

    @Test void batchAndByteBoundsRejectRatherThanTruncate() {
        var rows = new ArrayList<SourceRow>();
        for (int i = 0; i < 64; i++) rows.add(row(i));
        assertEquals("projection_missing", run(new Fake(rows, List.of())).status().code());
        rows.add(row(64));
        assertLimit(new Fake(rows, List.of()), "SOURCE_BATCHES");
        var first = row(0);
        assertLimit(new Fake(List.of(new SourceRow(first.admitted(), 2, "ADMITTED", 131073)), List.of()), "SOURCE_DOCUMENT_BYTES");
        rows.clear();
        for (int i = 0; i < 33; i++) rows.add(new SourceRow(row(i).admitted(), 2, "ADMITTED", 131072));
        assertLimit(new Fake(rows, List.of()), "SOURCE_PASS_BYTES");
        var projection = projection(summary(List.of(first)), 1).getFirst().projection();
        assertLimit(new Fake(List.of(first), List.of(new ProjectionRow(projection, 262145))), "PROJECTION_DOCUMENT_BYTES");
    }

    @Test void combinedSampleLimitAndInclusiveBounds() {
        var original = row(0).admitted().batch();
        for (int count : List.of(1000, 1001)) {
            var gaze = Collections.nCopies(count - 1, new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9));
            var gameplay = List.of(new AcceptedTelemetryBatch.GameplaySample(0, .1, .2, .3, .4, 0, 0, 0));
            var batch = new AcceptedTelemetryBatch(1, original.eventId(), original.batchId(), session, stream, 0,
                    original.capturedAt(), original.sentAt(), original.serverReceivedAt(), original.correlationId(), null,
                    gaze, gameplay, original.payloadSha256());
            var fake = new Fake(List.of(new SourceRow(new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.IN_ORDER),
                    2, "ADMITTED", 131072)), List.of());
            if (count == 1000) assertEquals("projection_missing", run(fake).status().code());
            else assertLimit(fake, "BATCH_SAMPLES");
        }
    }

    @Test void readsReceiveClippedDeadlineAndExpiredResultCannotSucceed() {
        var fake = new Fake(List.of(row(0)), List.of());
        fake.hook = () -> clock.addAndGet(Duration.ofMillis(1100).toNanos());
        assertEquals("projection_missing", run(fake).status().code());
        assertEquals(Duration.ofMillis(1700), fake.bounds.get(3).queryTimeout());
        clock.set(0);
        fake.calls.clear();
        fake.hook = () -> clock.addAndGet(Duration.ofMillis(1300).toNanos());
        assertEquals("deadline_exceeded", run(fake).status().code());
        assertEquals(4, fake.calls.size());
    }

    @Test void calculationExhaustionStopsBeforeSecondSourceRead() {
        var fake = new Fake(List.of(row(0)), List.of());
        var service = new PreviewBaselineProjectionRebuildService(fake, rows -> {
            clock.set(Duration.ofSeconds(5).toNanos());
            return summary(List.of(row(0)));
        }, clock::get);
        assertEquals("deadline_exceeded", service.preview(session, stream, "baseline-v1").await().indefinitely().status().code());
        assertEquals(List.of("P", "S"), fake.calls);
    }

    @Test void failuresNullsAndAsyncExceptionsNeverLeakOrRetry() {
        for (var problem : Arrays.<Throwable>asList(new IllegalStateException("private-content"),
                new ObservationException(ReadFailure.SOURCE_INVALID), new ObservationException(ReadFailure.PROJECTION_INVALID))) {
            var fake = new Fake(List.of(), List.of());
            fake.problem = problem;
            var result = run(fake);
            assertEquals(problem instanceof ObservationException o ? o.failure().name().toLowerCase(Locale.ROOT) : "unavailable",
                    result.status().code());
            assertFalse(result.toString().contains("private-content"));
            assertEquals(1, fake.calls.size());
        }
        var fake = new Fake(List.of(), List.of());
        fake.nullUni = true;
        assertEquals("unavailable", run(fake).status().code());
        fake.nullUni = false;
        fake.projection = null;
        assertEquals("unavailable", run(fake).status().code());
    }

    @Test void eachSubscriptionStartsNewObservationAndBudget() {
        var fake = new Fake(List.of(row(0)), List.of());
        var service = new PreviewBaselineProjectionRebuildService(fake, calculator::calculate, clock::get);
        var preview = service.preview(session, stream, "baseline-v1");
        assertTrue(fake.calls.isEmpty());
        assertEquals("projection_missing", preview.await().indefinitely().status().code());
        clock.set(Duration.ofSeconds(100).toNanos());
        assertEquals("projection_missing", preview.await().indefinitely().status().code());
        assertEquals(8, fake.calls.size());
    }

    @Test void stalledAsyncReadIsBoundedWithoutRetry() {
        var fake = new Fake(List.of(), List.of());
        fake.stalled = true;
        fake.hook = () -> clock.set(Duration.ofSeconds(5).toNanos() - Duration.ofMillis(5).toNanos());
        assertEquals("deadline_exceeded", run(fake).status().code());
        assertEquals(List.of("P"), fake.calls);
    }

    @Test void mutablePortListCannotChangeRetainedFirstObservation() {
        var rows = new ArrayList<>(List.of(row(0)));
        var fake = new Fake(rows, List.of());
        var service = new PreviewBaselineProjectionRebuildService(fake, batches -> {
            rows.set(0, row(1));
            return calculator.calculate(batches);
        }, clock::get);
        assertEquals("source_changed", service.preview(session, stream, "baseline-v1").await().indefinitely().status().code());
    }

    @Test void signedMonotonicClockOriginIsNotWallTime() {
        clock.set(Long.MIN_VALUE + 10);
        assertEquals("projection_missing", run(new Fake(List.of(row(0)), List.of())).status().code());
        clock.set(Long.MAX_VALUE - 10);
        var fake = new Fake(List.of(row(0)), List.of());
        fake.hook = () -> clock.addAndGet(20);
        assertEquals("projection_missing", run(fake).status().code());
    }

    @Test void maximumTotalSamplesAreAdmittedAndMalformedSamplesAreRejected() {
        var rows = new ArrayList<SourceRow>();
        for (int i = 0; i < 64; i++) {
            var b = row(i).admitted().batch();
            var batch = new AcceptedTelemetryBatch(1, b.eventId(), b.batchId(), session, stream, i,
                    b.capturedAt(), b.sentAt(), b.serverReceivedAt(), b.correlationId(), null,
                    Collections.nCopies(1000, new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), b.payloadSha256());
            rows.add(new SourceRow(new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.IN_ORDER), 2, "ADMITTED", 128));
        }
        assertEquals("projection_missing", run(new Fake(rows, List.of())).status().code());
        var b = row(0).admitted().batch();
        var invalid = new AcceptedTelemetryBatch(1, b.eventId(), b.batchId(), session, stream, 0,
                b.capturedAt(), b.sentAt(), b.serverReceivedAt(), b.correlationId(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, Double.NaN, .3, .9)), List.of(), b.payloadSha256());
        assertEquals("source_invalid", run(new Fake(List.of(new SourceRow(new AdmittedTelemetryBatch(invalid,
                TelemetryAdmissionQuality.IN_ORDER), 2, "ADMITTED", 128)), List.of())).status().code());
    }

    @Test void earlyAdapterLimitAndDeadlineFailuresKeepFixedOutcomes() {
        for (var limit : ObservationLimit.values()) {
            var fake = new Fake(List.of(), List.of());
            fake.problem = new ObservationLimitException(limit);
            var result = run(fake);
            assertEquals("limit_exceeded", result.status().code());
            assertEquals(limit.name(), result.limit().name());
            assertNull(result.sourceBatchCount());
        }
        var fake = new Fake(List.of(), List.of());
        fake.problem = new ObservationDeadlineException();
        assertEquals("deadline_exceeded", run(fake).status().code());
    }

    private BaselineStreamSummary summary(List<SourceRow> rows) {
        return calculator.calculate(rows.stream().map(SourceRow::admitted).toList());
    }
    private List<ProjectionRow> projection(BaselineStreamSummary summary, long revision) {
        return List.of(new ProjectionRow(new AnalyticsSummaryProjection(new AnalyticsProjectionIdentity(session, stream,
                "baseline-v1"), revision, summary), 256));
    }
    private void assertLimit(Fake fake, String category) {
        var result = run(fake);
        assertEquals("limit_exceeded", result.status().code());
        assertEquals(category, result.limit().name());
        assertNull(result.sourceBatchCount());
        assertNull(result.projectionRevision());
    }

    private PreviewBaselineProjectionRebuildService.Outcome run(Fake fake) {
        return new PreviewBaselineProjectionRebuildService(fake, calculator::calculate, clock::get)
                .preview(session, stream, "baseline-v1").await().atMost(Duration.ofSeconds(3));
    }

    private SourceRow row(long sequence) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), session, stream,
                sequence, time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "sha256-" + sequence);
        return new SourceRow(new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.IN_ORDER), 2,
                "ADMITTED", 128);
    }

    private final class Fake implements ProjectionRebuildObservationPort {
        List<SourceRow> source;
        List<ProjectionRow> projection;
        List<SourceRow> source1;
        List<ProjectionRow> projection1;
        final List<ReadBounds> bounds = new ArrayList<>();
        Runnable hook = () -> {};
        Throwable problem;
        boolean nullUni;
        boolean stalled;
        final List<String> calls = new ArrayList<>();
        Fake(List<SourceRow> source, List<ProjectionRow> projection) {
            this.source = source;
            this.projection = projection;
        }
        public Uni<List<SourceRow>> readSource(TelemetryStreamIdentity identity, ReadBounds bounds) {
            assertEquals(new TelemetryStreamIdentity(session, stream), identity);
            assertEquals(65, bounds.maxDocuments());
            assertTrue(bounds.queryTimeout().compareTo(Duration.ofSeconds(2)) <= 0);
            calls.add("S");
            this.bounds.add(bounds);
            hook.run();
            return Uni.createFrom().item(Collections.frequency(calls, "S") == 2 && source1 != null ? source1 : source);
        }
        public Uni<List<ProjectionRow>> readProjection(AnalyticsProjectionIdentity identity, ReadBounds bounds) {
            assertEquals(new AnalyticsProjectionIdentity(session, stream, "baseline-v1"), identity);
            assertEquals(2, bounds.maxDocuments());
            calls.add("P");
            this.bounds.add(bounds);
            hook.run();
            if (problem != null) return Uni.createFrom().failure(problem);
            if (nullUni) return null;
            if (stalled) return Uni.createFrom().nothing();
            return Uni.createFrom().item(Collections.frequency(calls, "P") == 2 && projection1 != null ? projection1 : projection);
        }
    }
}
