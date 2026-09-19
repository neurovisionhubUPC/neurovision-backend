package com.neurovision.rehab.analytics.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsDomainValueObjectsTest {

    @Test
    void exposesOnlyTheFrozenBaselineV1Profile() {
        assertEquals(List.of(AnalyticsCalculationProfile.BASELINE_V1),
                List.of(AnalyticsCalculationProfile.values()));
        var profile = AnalyticsCalculationProfile.BASELINE_V1;
        assertEquals("baseline-v1", profile.calculationVersion());
        assertEquals(10, profile.gridRows());
        assertEquals(10, profile.gridColumns());
        assertEquals(10, profile.confidenceBinCount());
        assertFalse(profile.filtersByConfidence());
        assertFalse(profile.weightsHeatmapByConfidence());
    }

    @Test
    void combinesAcceptedBatchAndPersistedAdmissionQualityWithoutInfrastructureMetadata() {
        var batch = batch();
        var admitted = new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.LATE_ARRIVAL);

        assertSame(batch, admitted.batch());
        assertEquals(TelemetryAdmissionQuality.LATE_ARRIVAL, admitted.admissionQuality());
        assertThrows(NullPointerException.class,
                () -> new AdmittedTelemetryBatch(null, TelemetryAdmissionQuality.INITIAL));
        assertThrows(NullPointerException.class, () -> new AdmittedTelemetryBatch(batch, null));
    }

    @Test
    void keepsBaselineSummaryCollectionsImmutableAndVersioned() {
        var histogram = new ArrayList<>(List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L));
        var cells = new ArrayList<Long>();
        for (int index = 0; index < 100; index++) cells.add(index == 32 ? 1L : 0L);
        var summary = summary(histogram, cells);

        histogram.set(9, 0L);
        cells.set(32, 0L);

        assertEquals("baseline-v1", summary.calculationVersion());
        assertEquals(1L, summary.confidenceHistogram().get(9));
        assertEquals(1L, summary.heatmap().cellCounts().get(32));
        assertThrows(UnsupportedOperationException.class, () -> summary.confidenceHistogram().add(1L));
        assertThrows(UnsupportedOperationException.class, () -> summary.heatmap().cellCounts().add(1L));
        assertTrue(summary.unknownSessionBoundary());
    }

    @Test
    void rejectsInconsistentOrNonFiniteResultValuesAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new BaselineStreamSummary.NumericSummary(
                1, OptionalDouble.of(0.2), OptionalDouble.of(0.8), OptionalDouble.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new BaselineStreamSummary.NumericSummary(
                0, OptionalDouble.of(0), OptionalDouble.empty(), OptionalDouble.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new BaselineStreamSummary.SequenceRange(4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new BaselineStreamSummary.TimeRange(
                        Instant.parse("2026-09-18T10:00:01Z"), Instant.parse("2026-09-18T10:00:00Z")));

        var invalidHistogram = new ArrayList<>(List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L));
        invalidHistogram.set(9, 2L);
        var cells = new ArrayList<Long>();
        for (int index = 0; index < 100; index++) cells.add(index == 32 ? 1L : 0L);
        assertThrows(IllegalArgumentException.class, () -> summary(invalidHistogram, cells));
    }

    private BaselineStreamSummary summary(List<Long> histogram, List<Long> cells) {
        var confidence = new BaselineStreamSummary.NumericSummary(1,
                OptionalDouble.of(.9), OptionalDouble.of(.9), OptionalDouble.of(.9));
        var empty = BaselineStreamSummary.NumericSummary.empty();
        var emptyOrdered = BaselineStreamSummary.OrderedNumericSummary.empty();
        var time = Instant.parse("2026-09-18T10:00:00Z");
        return new BaselineStreamSummary(
                AnalyticsCalculationProfile.BASELINE_V1,
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                5,
                5,
                List.of(),
                0,
                true,
                Optional.of(new BaselineStreamSummary.TimeRange(time, time)),
                new BaselineStreamSummary.SampleCounts(1, 0),
                confidence,
                histogram,
                new BaselineStreamSummary.Heatmap(10, 10, cells),
                new BaselineStreamSummary.Coverage(
                        Optional.of(new BaselineStreamSummary.BoundingBox(.2, .3, .2, .3, 0, 0, 0)),
                        1,
                        .01),
                new BaselineStreamSummary.PathMetrics(0, empty, 0),
                new BaselineStreamSummary.GameplayMetrics(empty, emptyOrdered, emptyOrdered, emptyOrdered));
    }

    private AcceptedTelemetryBatch batch() {
        var now = Instant.parse("2026-09-18T10:00:00Z");
        return new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, now, now.plusMillis(1), now.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "hash");
    }
}
