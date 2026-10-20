package com.neurovision.rehab.analytics.application.internal.commandservices;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ProjectionRebuildObservationPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplyBaselineProjectionRebuildServiceTest {
    private final UUID session = UUID.randomUUID(), stream = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final BaselineTelemetryMetricsCalculator calculator = new BaselineTelemetryMetricsCalculator();

    @Test void insertsMissingAndRepeatingFreshApplyIsANoop() {
        var fake = new Fake(List.of(row(0)));
        assertEquals("applied", apply(fake).status().code());
        assertEquals(List.of("P", "S", "S", "P", "W"), fake.calls);
        assertNull(fake.request.expected());
        assertEquals(1, fake.stored.revision());
        assertEquals("no_change", apply(fake).status().code());
        assertEquals(1, fake.writes);
        assertEquals(8, fake.reads);
    }

    @Test void advancesStaleUsingTheFullObservedProjection() {
        var fake = new Fake(List.of(row(0), row(1)));
        fake.stored = projection(fake.source.subList(0, 1));
        var before = fake.stored;
        assertEquals("applied", apply(fake).status().code());
        assertEquals(before, fake.request.expected());
        assertEquals(2, fake.request.candidate().admittedBatchCount());
        assertEquals(2, fake.stored.revision());
        assertEquals("no_change", apply(fake).status().code());
        assertEquals(1, fake.writes);
    }

    @Test void concurrentInsertOrNewerProjectionCannotBeOverwritten() {
        for (boolean initiallyMissing : List.of(true, false)) {
            var fake = new Fake(List.of(row(0), row(1)));
            if (!initiallyMissing) fake.stored = projection(fake.source.subList(0, 1));
            var concurrent = projection(fake.source);
            fake.beforeWrite = () -> fake.stored = concurrent;
            assertEquals("conflict", apply(fake).status().code());
            assertSame(concurrent, fake.stored);
            assertEquals(1, fake.writes);
        }
    }

    @Test void sameRevisionContentChangeAlsoConflicts() {
        var fake = new Fake(List.of(row(0), row(1)));
        fake.stored = projection(fake.source.subList(0, 1));
        var changed = projection(List.of(row(9)));
        fake.beforeWrite = () -> fake.stored = changed;
        assertEquals("conflict", apply(fake).status().code());
        assertSame(changed, fake.stored);
        assertEquals(1, fake.writes);
    }

    @Test void equalRevisionMismatchAndAheadProjectionAreRefused() {
        for (var rows : List.of(List.of(row(9)), List.of(row(0), row(1)))) {
            var fake = new Fake(List.of(row(0)));
            fake.stored = projection(rows);
            var before = fake.stored;
            assertEquals("refused", apply(fake).status().code());
            assertSame(before, fake.stored);
            assertEquals(0, fake.writes);
        }
    }

    @Test void changedSourceAndProjectionNeverReachWriter() {
        var sourceChange = new Fake(List.of(row(0)));
        sourceChange.secondSource = List.of(row(1));
        assertEquals("refused", apply(sourceChange).status().code());
        assertEquals(0, sourceChange.writes);
        var projectionChange = new Fake(List.of(row(0)));
        projectionChange.afterFinalProjection = () -> {};
        projectionChange.changeFinalProjection = true;
        assertEquals("refused", apply(projectionChange).status().code());
        assertEquals(0, projectionChange.writes);
    }

    @Test void unauthorizedAndInvalidInputHaveNoEffects() {
        var fake = new Fake(List.of(row(0)));
        assertEquals("not_authorized", service(fake).apply(session, stream, "baseline-v1", false)
                .await().atMost(Duration.ofSeconds(3)).status().code());
        assertTrue(fake.calls.isEmpty());
        assertEquals("refused", service(fake).apply(null, stream, "baseline-v1", true)
                .await().atMost(Duration.ofSeconds(3)).status().code());
        assertEquals("refused", service(fake).apply(session, stream, "other", true)
                .await().atMost(Duration.ofSeconds(3)).status().code());
        assertTrue(fake.calls.isEmpty());
    }

    @Test void limitsInvalidSourceAndEmptySourceNeverReachWriter() {
        var oversized = new ArrayList<ProjectionRebuildObservationPort.SourceRow>();
        for (int i = 0; i < 65; i++) oversized.add(row(i));
        var invalid = row(0);
        for (var rows : List.of(oversized, List.of(new ProjectionRebuildObservationPort.SourceRow(
                invalid.admitted(), 2, "BROKEN", 128)), List.<ProjectionRebuildObservationPort.SourceRow>of())) {
            var fake = new Fake(rows);
            assertEquals("refused", apply(fake).status().code());
            assertEquals(0, fake.writes);
        }
    }

    @Test void expiredBeforeWriteNeverDispatches() {
        var fake = new Fake(List.of(row(0)));
        fake.afterFinalProjection = () -> clock.set(5_000_000_000L);
        assertEquals("refused", apply(fake).status().code());
        assertEquals(0, fake.writes);
    }

    @Test void writeUsesOnlyRemainingPreparationBudgetAndLateAckIsUnknown() {
        var fake = new Fake(List.of(row(0)));
        fake.afterFinalProjection = () -> clock.set(4_500_000_000L);
        fake.beforeWrite = () -> clock.set(5_000_000_000L);
        assertEquals("write_outcome_unknown", apply(fake).status().code());
        assertEquals(Duration.ofMillis(500), fake.writeTimeout);
        assertNotNull(fake.stored);
        assertEquals(1, fake.writes);
    }

    @Test void synchronousWriteOverheadCannotExceedItsTwoSecondCallBudget() {
        var fake = new Fake(List.of(row(0)));
        fake.beforeWrite = () -> clock.set(2_000_000_000L);
        assertEquals("write_outcome_unknown", apply(fake).status().code());
        assertEquals(Duration.ofSeconds(2), fake.writeTimeout);
        assertNotNull(fake.stored);
        assertEquals(1, fake.writes);
    }

    @Test void lostAckMayCoexistWithAWriteAndNeverResends() {
        var fake = new Fake(List.of(row(0)));
        fake.loseAck = true;
        assertEquals("write_outcome_unknown", apply(fake).status().code());
        assertNotNull(fake.stored);
        assertEquals(1, fake.writes);
        assertEquals("no_change", apply(fake).status().code());
        assertEquals(1, fake.writes);
    }

    @Test void synchronousFailureNullUniAndNullAckAreUnknown() {
        for (String mode : List.of("throw", "null_uni", "null_ack", "stall", "wrong_ack")) {
            var fake = new Fake(List.of(row(0)));
            fake.mode = mode;
            assertEquals("write_outcome_unknown", apply(fake).status().code());
            assertEquals(1, fake.writes);
        }
    }

    @Test void noEagerSubscriptionAndEachSubscriptionReprepares() {
        var fake = new Fake(List.of(row(0)));
        var uni = service(fake).apply(session, stream, "baseline-v1", true);
        assertTrue(fake.calls.isEmpty());
        assertEquals("applied", uni.await().atMost(Duration.ofSeconds(3)).status().code());
        assertEquals("no_change", uni.await().atMost(Duration.ofSeconds(3)).status().code());
        assertEquals(1, fake.writes);
    }

    @Test void outputAndWriteRequestNeverPrintPrivateData() {
        var fake = new Fake(List.of(row(0)));
        var result = apply(fake);
        for (String text : List.of(result.toString(), fake.request.toString())) {
            assertFalse(text.contains(session.toString()));
            assertFalse(text.contains(stream.toString()));
            assertFalse(text.contains("sha256-"));
            assertFalse(text.contains("GazeSample"));
        }
        assertEquals("baseline-v1", result.calculationVersion());
        assertEquals("optimistic_repeated_read", result.consistencyMode());
    }

    private ApplyBaselineProjectionRebuildService service(Fake fake) {
        return new ApplyBaselineProjectionRebuildService(fake, fake, calculator::calculate, clock::get);
    }
    private ApplyBaselineProjectionRebuildService.Outcome apply(Fake fake) {
        return service(fake).apply(session, stream, "baseline-v1", true).await().atMost(Duration.ofSeconds(3));
    }
    private AnalyticsSummaryProjection projection(List<ProjectionRebuildObservationPort.SourceRow> rows) {
        var summary = calculator.calculate(rows.stream().map(ProjectionRebuildObservationPort.SourceRow::admitted).toList());
        return new AnalyticsSummaryProjection(new AnalyticsProjectionIdentity(session, stream, "baseline-v1"), rows.size(), summary);
    }
    private ProjectionRebuildObservationPort.SourceRow row(long sequence) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), session, stream,
                sequence, time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "sha256-" + sequence);
        return new ProjectionRebuildObservationPort.SourceRow(new AdmittedTelemetryBatch(batch,
                TelemetryAdmissionQuality.IN_ORDER), 2, "ADMITTED", 128);
    }

    private final class Fake implements ProjectionRebuildObservationPort, ConditionalProjectionRebuildWritePort {
        final List<SourceRow> source;
        List<SourceRow> secondSource;
        AnalyticsSummaryProjection stored;
        final List<String> calls = new ArrayList<>();
        int reads, sources, projections, writes;
        boolean loseAck, changeFinalProjection;
        String mode = "ok";
        Runnable beforeWrite = () -> {}, afterFinalProjection = () -> {};
        WriteRequest request;
        Duration writeTimeout;
        Fake(List<SourceRow> source) { this.source = source; }
        public Uni<List<SourceRow>> readSource(TelemetryStreamIdentity identity, ReadBounds bounds) {
            calls.add("S"); reads++; sources++;
            return Uni.createFrom().item(sources % 2 == 0 && secondSource != null ? secondSource : source);
        }
        public Uni<List<ProjectionRow>> readProjection(AnalyticsProjectionIdentity identity, ReadBounds bounds) {
            calls.add("P"); reads++; projections++;
            if (projections % 2 == 0) {
                if (changeFinalProjection) stored = projection(source);
                afterFinalProjection.run();
            }
            return Uni.createFrom().item(stored == null ? List.of() : List.of(new ProjectionRow(stored, 256)));
        }
        public Uni<WriteResult> writeIfUnchanged(WriteRequest requested, Duration timeout) {
            calls.add("W"); writes++; request = requested; writeTimeout = timeout;
            beforeWrite.run();
            if (mode.equals("throw")) throw new IllegalStateException("private-failure");
            if (mode.equals("null_uni")) return null;
            if (mode.equals("null_ack")) return Uni.createFrom().nullItem();
            if (mode.equals("stall")) return Uni.createFrom().nothing();
            if (mode.equals("wrong_ack")) return Uni.createFrom().item(WriteResult.REPLACED);
            if (!Objects.equals(stored, requested.expected())) return Uni.createFrom().item(WriteResult.PRECONDITION_FAILED);
            var candidate = requested.candidate();
            stored = new AnalyticsSummaryProjection(candidate.identity(), candidate.admittedBatchCount(), candidate.summary());
            if (loseAck) return Uni.createFrom().failure(new IllegalStateException("private-lost-ack"));
            return Uni.createFrom().item(requested.expected() == null ? WriteResult.INSERTED : WriteResult.REPLACED);
        }
    }
}
