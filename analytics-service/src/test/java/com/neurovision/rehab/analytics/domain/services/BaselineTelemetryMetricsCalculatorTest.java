package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaselineTelemetryMetricsCalculatorTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-18T10:00:00Z");
    private final BaselineTelemetryMetricsCalculator calculator = new BaselineTelemetryMetricsCalculator();

    @Test
    void calculatesFrozenBaselineGeometryTimingAndGameplayFormulas() {
        var gaze = List.of(
                new AcceptedTelemetryBatch.GazeSample(0, 0, 0, 0),
                new AcceptedTelemetryBatch.GazeSample(1_000, .3, .4, 1));
        var gameplay = List.of(
                new AcceptedTelemetryBatch.GameplaySample(0, 0, 0, .3, .4, 10, 2, .2),
                new AcceptedTelemetryBatch.GameplaySample(2_000, .3, .4, .3, .4, 15, 4, .8));

        var summary = calculator.calculate(List.of(admitted(
                batch(1, UUID.randomUUID(), SESSION, STREAM, 5, START, gaze, gameplay),
                TelemetryAdmissionQuality.LATE_ARRIVAL)));

        assertEquals("baseline-v1", summary.calculationVersion());
        assertEquals(5, summary.minimumSequence());
        assertEquals(5, summary.maximumSequence());
        assertEquals(5, summary.highSequence());
        assertTrue(summary.missingSequenceRanges().isEmpty());
        assertEquals(1, summary.lateArrivalCount());
        assertTrue(summary.unknownSessionBoundary());
        assertEquals(new BaselineStreamSummary.SampleCounts(2, 2), summary.sampleCounts());
        assertEquals(START, summary.observedTimeRange().orElseThrow().minimum());
        assertEquals(Duration.ofSeconds(2), summary.observedTimeRange().orElseThrow().span());

        assertStats(summary.confidence(), 2, 0, 1, .5);
        assertEquals(1, summary.confidenceHistogram().get(0));
        assertEquals(1, summary.confidenceHistogram().get(9));
        assertEquals(1, summary.heatmap().cellCounts().get(0));
        assertEquals(1, summary.heatmap().cellCounts().get(43));
        var box = summary.coverage().boundingBox().orElseThrow();
        assertEquals(.3, box.width(), 1e-12);
        assertEquals(.4, box.height(), 1e-12);
        assertEquals(.12, box.area(), 1e-12);
        assertEquals(2, summary.coverage().occupiedCellCount());
        assertEquals(.02, summary.coverage().occupiedCellRatio(), 1e-12);
        assertEquals(.5, summary.gazePath().totalDistance(), 1e-12);
        assertStats(summary.gazePath().velocity(), 1, .5, .5, .5);

        assertStats(summary.gameplay().playerTargetDistance(), 2, 0, .5, .25);
        assertOrdered(summary.gameplay().score(), 2, 10, 15, 12.5, 10, 15, 5);
        assertOrdered(summary.gameplay().speed(), 2, 2, 4, 3, 2, 4, 2);
        assertOrdered(summary.gameplay().progress(), 2, .2, .8, .5, .2, .8, .6);
    }

    @Test
    void neverBridgesSequenceGapsOrBackwardTimeAndCountsEqualTimeVelocityExclusion() {
        var sequenceZero = batch(1, UUID.randomUUID(), SESSION, STREAM, 0, START,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, 0, 0, .5),
                        new AcceptedTelemetryBatch.GazeSample(0, .1, 0, .5)), List.of());
        var afterGap = batch(1, UUID.randomUUID(), SESSION, STREAM, 2, START,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .9, .9, .5)), List.of());
        var backward = batch(1, UUID.randomUUID(), SESSION, STREAM, 3, START.minusSeconds(1),
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .8, .8, .5)), List.of());

        var summary = calculator.calculate(List.of(
                admitted(sequenceZero, TelemetryAdmissionQuality.INITIAL),
                admitted(afterGap, TelemetryAdmissionQuality.GAP_DETECTED),
                admitted(backward, TelemetryAdmissionQuality.LATE_ARRIVAL)));

        assertEquals(List.of(new BaselineStreamSummary.SequenceRange(1, 1)), summary.missingSequenceRanges());
        assertEquals(.1, summary.gazePath().totalDistance(), 1e-12);
        assertEquals(0, summary.gazePath().velocity().count());
        assertEquals(1, summary.gazePath().excludedNonPositiveDeltaCount());
        assertEquals(1, summary.lateArrivalCount());
    }

    @Test
    void convergesToTheSameResultForShuffledBatchInput() {
        var first = admitted(batch(1, UUID.randomUUID(), SESSION, STREAM, 8, START,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .1, .1, .6)), List.of()),
                TelemetryAdmissionQuality.INITIAL);
        var second = admitted(batch(1, UUID.randomUUID(), SESSION, STREAM, 9, START.plusSeconds(1),
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .1, .8)), List.of()),
                TelemetryAdmissionQuality.LATE_ARRIVAL);

        assertEquals(calculator.calculate(List.of(first, second)), calculator.calculate(List.of(second, first)));
    }

    @Test
    void keepsCompensatedMeanWithinObservedBoundsForRepeatedDecimals() {
        var gaze = List.of(
                new AcceptedTelemetryBatch.GazeSample(0, .1, .1, .1),
                new AcceptedTelemetryBatch.GazeSample(1, .1, .1, .1),
                new AcceptedTelemetryBatch.GazeSample(2, .1, .1, .1));

        var summary = calculator.calculate(List.of(admitted(
                batch(1, UUID.randomUUID(), SESSION, STREAM, 0, START, gaze, List.of()),
                TelemetryAdmissionQuality.INITIAL)));

        assertEquals(.1, summary.confidence().mean().orElseThrow());
        assertTrue(summary.confidence().mean().orElseThrow() <= summary.confidence().maximum().orElseThrow());
    }

    @Test
    void rejectsMixedDuplicateAndInvalidDirectDomainInput() {
        var valid = batch(1, UUID.randomUUID(), SESSION, STREAM, 0, START,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .1, .1, .5)), List.of());
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(
                admitted(valid, TelemetryAdmissionQuality.INITIAL),
                admitted(batch(2, UUID.randomUUID(), SESSION, STREAM, 1, START,
                        valid.gazeSamples(), List.of()), TelemetryAdmissionQuality.IN_ORDER))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(
                admitted(valid, TelemetryAdmissionQuality.INITIAL),
                admitted(batch(1, UUID.randomUUID(), UUID.randomUUID(), STREAM, 1, START,
                        valid.gazeSamples(), List.of()), TelemetryAdmissionQuality.IN_ORDER))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(
                admitted(valid, TelemetryAdmissionQuality.INITIAL),
                admitted(batch(1, UUID.randomUUID(), SESSION, UUID.randomUUID(), 1, START,
                        valid.gazeSamples(), List.of()), TelemetryAdmissionQuality.IN_ORDER))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(
                admitted(valid, TelemetryAdmissionQuality.INITIAL),
                admitted(batch(1, valid.batchId(), SESSION, STREAM, 1, START,
                        valid.gazeSamples(), List.of()), TelemetryAdmissionQuality.IN_ORDER))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(
                admitted(valid, TelemetryAdmissionQuality.INITIAL),
                admitted(batch(1, UUID.randomUUID(), SESSION, STREAM, 0, START,
                        valid.gazeSamples(), List.of()), TelemetryAdmissionQuality.LATE_ARRIVAL))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(admitted(
                batch(1, UUID.randomUUID(), SESSION, STREAM, 1, START,
                        List.of(new AcceptedTelemetryBatch.GazeSample(0, Double.NaN, .1, .5)), List.of()),
                TelemetryAdmissionQuality.IN_ORDER))));
    }

    private AdmittedTelemetryBatch admitted(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality) {
        return new AdmittedTelemetryBatch(batch, quality);
    }

    private AcceptedTelemetryBatch batch(int schemaVersion, UUID batchId, UUID sessionId, UUID streamId,
            long sequence, Instant capturedAt, List<AcceptedTelemetryBatch.GazeSample> gaze,
            List<AcceptedTelemetryBatch.GameplaySample> gameplay) {
        return new AcceptedTelemetryBatch(schemaVersion, UUID.randomUUID(), batchId, sessionId, streamId,
                sequence, capturedAt, capturedAt.plusSeconds(3), capturedAt.plusSeconds(4), UUID.randomUUID(),
                null, gaze, gameplay, "hash-" + batchId);
    }

    private void assertStats(BaselineStreamSummary.NumericSummary summary,
            long count, double minimum, double maximum, double mean) {
        assertEquals(count, summary.count());
        assertEquals(minimum, summary.minimum().orElseThrow(), 1e-12);
        assertEquals(maximum, summary.maximum().orElseThrow(), 1e-12);
        assertEquals(mean, summary.mean().orElseThrow(), 1e-12);
    }

    private void assertOrdered(BaselineStreamSummary.OrderedNumericSummary summary, long count,
            double minimum, double maximum, double mean, double first, double last, double delta) {
        assertStats(summary.distribution(), count, minimum, maximum, mean);
        assertEquals(first, summary.first().orElseThrow(), 1e-12);
        assertEquals(last, summary.last().orElseThrow(), 1e-12);
        assertEquals(delta, summary.signedDelta().orElseThrow(), 1e-12);
    }
}
