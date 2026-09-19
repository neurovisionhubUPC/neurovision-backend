package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable read model for one completed baseline heatmap projection. */
public record HeatmapProjection(
        AnalyticsProjectionIdentity identity,
        long revision,
        int sourceSchemaVersion,
        CoordinateSpace coordinateSpace,
        CellWeight cellWeight,
        boolean confidenceFiltered,
        int rows,
        int columns,
        List<Long> cellCounts,
        long minimumSequence,
        long maximumSequence,
        long highSequence,
        List<SequenceRange> missingSequenceRanges,
        long lateArrivalCount,
        boolean unknownSessionBoundary,
        long gazeSampleCount,
        Optional<TimeRange> observedTimeRange) {

    public HeatmapProjection {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(coordinateSpace, "coordinateSpace");
        Objects.requireNonNull(cellWeight, "cellWeight");
        Objects.requireNonNull(cellCounts, "cellCounts");
        Objects.requireNonNull(missingSequenceRanges, "missingSequenceRanges");
        Objects.requireNonNull(observedTimeRange, "observedTimeRange");
        cellCounts = List.copyOf(cellCounts);
        missingSequenceRanges = List.copyOf(missingSequenceRanges);

        require(AnalyticsCalculationProfile.BASELINE_V1.calculationVersion()
                .equals(identity.calculationVersion()), "unsupported calculation version");
        require(revision > 0, "revision must be positive");
        require(sourceSchemaVersion == 1, "sourceSchemaVersion must be 1");
        require(coordinateSpace == CoordinateSpace.NORMALIZED_ZERO_TO_ONE,
                "baseline-v1 coordinates must be normalized");
        require(cellWeight == CellWeight.RAW_GAZE_SAMPLE_COUNT,
                "baseline-v1 cells must contain raw gaze counts");
        require(!confidenceFiltered, "baseline-v1 must not filter by confidence");
        require(rows == AnalyticsCalculationProfile.BASELINE_V1.gridRows()
                        && columns == AnalyticsCalculationProfile.BASELINE_V1.gridColumns(),
                "baseline-v1 heatmap must be 10x10");
        require(cellCounts.size() == Math.multiplyExact(rows, columns),
                "heatmap must contain exactly 100 row-major cells");
        require(cellCounts.stream().allMatch(value -> value != null && value >= 0),
                "cell counts must be non-negative");
        require(gazeSampleCount >= 0, "gazeSampleCount must be non-negative");
        require(sum(cellCounts) == gazeSampleCount, "cell counts must count every gaze sample");
        require(minimumSequence >= 0 && maximumSequence >= minimumSequence, "invalid sequence range");
        require(highSequence == maximumSequence, "highSequence must equal maximumSequence");
        require(lateArrivalCount >= 0, "lateArrivalCount must be non-negative");
        require(unknownSessionBoundary, "baseline-v1 session boundary must remain unknown");
        validateMissingRanges(missingSequenceRanges, minimumSequence, maximumSequence);
    }

    public enum CoordinateSpace {
        NORMALIZED_ZERO_TO_ONE
    }

    public enum CellWeight {
        RAW_GAZE_SAMPLE_COUNT
    }

    public record SequenceRange(long fromInclusive, long toInclusive) {
        public SequenceRange {
            require(fromInclusive >= 0 && toInclusive >= fromInclusive, "invalid missing sequence range");
        }
    }

    public record TimeRange(Instant minimum, Instant maximum) {
        public TimeRange {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            require(!maximum.isBefore(minimum), "maximum time precedes minimum");
        }
    }

    private static void validateMissingRanges(List<SequenceRange> ranges, long minimum, long maximum) {
        long previousEnd = -1;
        for (var range : ranges) {
            require(range != null, "missing sequence ranges must not contain null");
            require(range.fromInclusive() > minimum && range.toInclusive() < maximum,
                    "missing sequence ranges must remain inside observed bounds");
            require(range.fromInclusive() > previousEnd, "missing sequence ranges must be ordered and disjoint");
            previousEnd = range.toInclusive();
        }
    }

    private static long sum(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).reduce(0, Math::addExact);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
