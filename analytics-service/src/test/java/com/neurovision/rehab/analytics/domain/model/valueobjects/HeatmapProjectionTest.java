package com.neurovision.rehab.analytics.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CellWeight;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection.CoordinateSpace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeatmapProjectionTest {
    private static final AnalyticsProjectionIdentity IDENTITY = new AnalyticsProjectionIdentity(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "baseline-v1");

    @Test
    void exposesAnImmutableStrictBaselineHeatmapReadModel() {
        var mutableCells = new ArrayList<>(cells(2));
        var mutableRanges = new ArrayList<>(List.of(new HeatmapProjection.SequenceRange(1, 1)));

        var projection = projection(mutableCells, mutableRanges);
        mutableCells.clear();
        mutableRanges.clear();

        assertEquals(10, projection.rows());
        assertEquals(10, projection.columns());
        assertEquals(100, projection.cellCounts().size());
        assertEquals(2, projection.gazeSampleCount());
        assertEquals(CoordinateSpace.NORMALIZED_ZERO_TO_ONE, projection.coordinateSpace());
        assertEquals(CellWeight.RAW_GAZE_SAMPLE_COUNT, projection.cellWeight());
        assertEquals(1, projection.missingSequenceRanges().size());
        assertThrows(UnsupportedOperationException.class, () -> projection.cellCounts().add(1L));
        assertThrows(UnsupportedOperationException.class,
                () -> projection.missingSequenceRanges().add(new HeatmapProjection.SequenceRange(3, 3)));
    }

    @Test
    void rejectsUnsupportedOrInternallyInconsistentHeatmapData() {
        assertThrows(IllegalArgumentException.class, () -> copy("baseline-v2", 10, 10, cells(2), 2, 2));
        assertThrows(IllegalArgumentException.class, () -> copy("baseline-v1", 5, 20, cells(2), 2, 2));
        assertThrows(IllegalArgumentException.class, () -> copy("baseline-v1", 10, 10, cells(2), 3, 2));
        assertThrows(IllegalArgumentException.class, () -> copy("baseline-v1", 10, 10, cells(2), 2, 1));
    }

    @Test
    void rejectsCompletenessMetadataOutsideTheObservedSequenceBounds() {
        assertThrows(IllegalArgumentException.class, () -> projection(cells(2),
                List.of(new HeatmapProjection.SequenceRange(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> projection(cells(2), List.of(
                new HeatmapProjection.SequenceRange(1, 1),
                new HeatmapProjection.SequenceRange(1, 1))));
    }

    private HeatmapProjection projection(List<Long> cells, List<HeatmapProjection.SequenceRange> ranges) {
        return new HeatmapProjection(
                IDENTITY, 2, 1, CoordinateSpace.NORMALIZED_ZERO_TO_ONE,
                CellWeight.RAW_GAZE_SAMPLE_COUNT, false,
                10, 10, cells, 0, 2, 2, ranges, 1, true, 2,
                Optional.of(new HeatmapProjection.TimeRange(
                        Instant.parse("2026-09-18T10:00:00Z"),
                        Instant.parse("2026-09-18T10:00:02Z"))));
    }

    private HeatmapProjection copy(String version, int rows, int columns, List<Long> cells,
            long gazeCount, long highSequence) {
        return new HeatmapProjection(
                new AnalyticsProjectionIdentity(IDENTITY.therapySessionId(), IDENTITY.streamId(), version),
                2, 1, CoordinateSpace.NORMALIZED_ZERO_TO_ONE, CellWeight.RAW_GAZE_SAMPLE_COUNT, false,
                rows, columns, cells, 0, 2, highSequence,
                List.of(new HeatmapProjection.SequenceRange(1, 1)), 1, true, gazeCount, Optional.empty());
    }

    private static List<Long> cells(long gazeCount) {
        var cells = new ArrayList<Long>(java.util.Collections.nCopies(100, 0L));
        cells.set(0, gazeCount);
        return cells;
    }
}
