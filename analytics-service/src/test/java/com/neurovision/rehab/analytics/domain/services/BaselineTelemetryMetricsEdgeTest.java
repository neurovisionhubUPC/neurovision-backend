package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaselineTelemetryMetricsEdgeTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-18T10:00:00Z");
    private final BaselineTelemetryMetricsCalculator calculator = new BaselineTelemetryMetricsCalculator();

    @Test
    void assignsNormalizedGridAndConfidenceBoundaryValuesToStableBins() {
        var gaze = List.of(
                gaze(0, 0, 0, 0),
                gaze(1, 1, 1, .1),
                gaze(2, .5, .5, .9),
                gaze(3, .99, .99, 1));

        var summary = calculate(admitted(batch(0, START, gaze, List.of()), TelemetryAdmissionQuality.INITIAL));

        assertEquals(1, summary.heatmap().cellCounts().get(0));
        assertEquals(1, summary.heatmap().cellCounts().get(55));
        assertEquals(2, summary.heatmap().cellCounts().get(99));
        assertEquals(1, summary.confidenceHistogram().get(0));
        assertEquals(1, summary.confidenceHistogram().get(1));
        assertEquals(2, summary.confidenceHistogram().get(9));
        assertEquals(3, summary.coverage().occupiedCellCount());
        assertEquals(.03, summary.coverage().occupiedCellRatio(), 1e-12);
        assertEquals(1, summary.coverage().boundingBox().orElseThrow().area(), 1e-12);
    }

    @Test
    void handlesOnePointAndEitherEmptyModalityWithoutInventingSequencePrehistory() {
        var gazeOnly = calculate(admitted(batch(5, START,
                List.of(gaze(0, .2, .3, .8)), List.of()), TelemetryAdmissionQuality.INITIAL));
        assertEquals(5, gazeOnly.minimumSequence());
        assertEquals(5, gazeOnly.highSequence());
        assertTrue(gazeOnly.missingSequenceRanges().isEmpty());
        assertTrue(gazeOnly.unknownSessionBoundary());
        assertEquals(0, gazeOnly.gazePath().totalDistance());
        assertEquals(0, gazeOnly.gazePath().velocity().count());
        assertEquals(0, gazeOnly.gameplay().score().distribution().count());

        var gameplayOnly = calculate(admitted(batch(5, START, List.of(),
                List.of(gameplay(0, 3, 9.75, .4))), TelemetryAdmissionQuality.INITIAL));
        assertEquals(0, gameplayOnly.confidence().count());
        assertEquals(0, gameplayOnly.confidenceHistogram().stream().mapToLong(Long::longValue).sum());
        assertEquals(0, gameplayOnly.heatmap().cellCounts().stream().mapToLong(Long::longValue).sum());
        assertTrue(gameplayOnly.coverage().boundingBox().isEmpty());
        assertEquals(0, gameplayOnly.coverage().occupiedCellRatio());
        assertEquals(1, gameplayOnly.gameplay().score().distribution().count());
    }

    @Test
    void lateGapFillConvergesBySequenceOrderAndChangesOnlyPersistedLateCount() {
        var zero = admitted(batch(0, START, List.of(gaze(0, 0, 0, .5)), List.of()),
                TelemetryAdmissionQuality.INITIAL);
        var two = admitted(batch(2, START.plusSeconds(2), List.of(gaze(0, .2, 0, .5)), List.of()),
                TelemetryAdmissionQuality.GAP_DETECTED);
        var oneLate = admitted(batch(1, START.plusSeconds(1), List.of(gaze(0, .1, 0, .5)), List.of()),
                TelemetryAdmissionQuality.LATE_ARRIVAL);

        var gap = calculator.calculate(List.of(zero, two));
        assertEquals(List.of(new BaselineStreamSummary.SequenceRange(1, 1)), gap.missingSequenceRanges());
        assertEquals(0, gap.gazePath().totalDistance());

        var lateArrivalOrder = calculator.calculate(List.of(zero, two, oneLate));
        var shuffled = calculator.calculate(List.of(oneLate, two, zero));
        assertEquals(lateArrivalOrder, shuffled);
        assertTrue(lateArrivalOrder.missingSequenceRanges().isEmpty());
        assertEquals(.2, lateArrivalOrder.gazePath().totalDistance(), 1e-12);
        assertEquals(1, lateArrivalOrder.lateArrivalCount());

        var oneInOrder = new AdmittedTelemetryBatch(oneLate.batch(), TelemetryAdmissionQuality.IN_ORDER);
        var reclassified = calculator.calculate(List.of(two, zero, oneInOrder));
        assertEquals(0, reclassified.lateArrivalCount());
        assertEquals(lateArrivalOrder.gazePath(), reclassified.gazePath());
        assertEquals(lateArrivalOrder.heatmap(), reclassified.heatmap());
        assertEquals(lateArrivalOrder.observedTimeRange(), reclassified.observedTimeRange());
    }

    @Test
    void keepsSourceSpeedAndCalculatesSignedGameplayDeltasInSourceOrder() {
        var gameplay = List.of(
                gameplay(0, 10, 123.456, .8),
                gameplay(1, 4, 7.25, .2));

        var summary = calculate(admitted(batch(0, START, List.of(), gameplay),
                TelemetryAdmissionQuality.INITIAL));

        assertEquals(-6, summary.gameplay().score().signedDelta().orElseThrow(), 1e-12);
        assertEquals(-.6, summary.gameplay().progress().signedDelta().orElseThrow(), 1e-12);
        assertEquals(123.456, summary.gameplay().speed().first().orElseThrow(), 1e-12);
        assertEquals(7.25, summary.gameplay().speed().last().orElseThrow(), 1e-12);
        assertEquals(7.25, summary.gameplay().speed().distribution().minimum().orElseThrow(), 1e-12);
        assertEquals(123.456, summary.gameplay().speed().distribution().maximum().orElseThrow(), 1e-12);
        assertEquals(7.25 - 123.456, summary.gameplay().speed().signedDelta().orElseThrow(), 1e-12);
    }

    @Test
    void rejectsInvalidRangesAndLeavesCallerInputsUnchanged() {
        assertInvalid(List.of(gaze(0, Double.POSITIVE_INFINITY, .2, .5)), List.of());
        assertInvalid(List.of(gaze(0, .2, .2, 1.01)), List.of());
        assertInvalid(List.of(), List.of(new AcceptedTelemetryBatch.GameplaySample(
                0, .1, .1, .2, .2, 1, -1, .5)));
        assertInvalid(List.of(), List.of(new AcceptedTelemetryBatch.GameplaySample(
                0, .1, .1, .2, .2, -1, 1, .5)));
        assertInvalid(List.of(), List.of(new AcceptedTelemetryBatch.GameplaySample(
                0, .1, .1, .2, .2, 1, 1, 1.01)));
        assertInvalid(List.of(gaze(2, .2, .2, .5), gaze(1, .3, .3, .5)), List.of());

        var mutableGaze = new ArrayList<>(List.of(gaze(0, .1, .1, .5)));
        var first = admitted(batch(0, START, mutableGaze, List.of()), TelemetryAdmissionQuality.INITIAL);
        mutableGaze.add(gaze(1, .9, .9, .5));
        var second = admitted(batch(1, START.plusSeconds(1), List.of(gaze(0, .2, .1, .5)), List.of()),
                TelemetryAdmissionQuality.IN_ORDER);
        var mutableInput = new ArrayList<>(List.of(second, first));
        var originalOrder = List.copyOf(mutableInput);

        var summary = calculator.calculate(mutableInput);

        assertEquals(originalOrder, mutableInput);
        assertEquals(2, summary.sampleCounts().gaze());
        assertFalse(summary.heatmap().cellCounts().get(99) > 0);
    }

    private void assertInvalid(List<AcceptedTelemetryBatch.GazeSample> gaze,
            List<AcceptedTelemetryBatch.GameplaySample> gameplay) {
        assertThrows(IllegalArgumentException.class, () -> calculate(admitted(
                batch(0, START, gaze, gameplay), TelemetryAdmissionQuality.INITIAL)));
    }

    private BaselineStreamSummary calculate(AdmittedTelemetryBatch batch) {
        return calculator.calculate(List.of(batch));
    }

    private AdmittedTelemetryBatch admitted(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality) {
        return new AdmittedTelemetryBatch(batch, quality);
    }

    private AcceptedTelemetryBatch batch(long sequence, Instant capturedAt,
            List<AcceptedTelemetryBatch.GazeSample> gaze,
            List<AcceptedTelemetryBatch.GameplaySample> gameplay) {
        var batchId = UUID.randomUUID();
        return new AcceptedTelemetryBatch(1, UUID.randomUUID(), batchId, SESSION, STREAM, sequence,
                capturedAt, capturedAt.plusSeconds(5), capturedAt.plusSeconds(6), UUID.randomUUID(), null,
                gaze, gameplay, "hash-" + batchId);
    }

    private AcceptedTelemetryBatch.GazeSample gaze(long offset, double x, double y, double confidence) {
        return new AcceptedTelemetryBatch.GazeSample(offset, x, y, confidence);
    }

    private AcceptedTelemetryBatch.GameplaySample gameplay(
            long offset, long score, double speed, double progress) {
        return new AcceptedTelemetryBatch.GameplaySample(
                offset, .1, .2, .3, .4, score, speed, progress);
    }
}
