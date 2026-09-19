package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

public record BaselineStreamSummary(
        AnalyticsCalculationProfile profile,
        int sourceSchemaVersion,
        UUID therapySessionId,
        UUID streamId,
        long minimumSequence,
        long maximumSequence,
        List<SequenceRange> missingSequenceRanges,
        long lateArrivalCount,
        boolean unknownSessionBoundary,
        Optional<TimeRange> observedTimeRange,
        SampleCounts sampleCounts,
        NumericSummary confidence,
        List<Long> confidenceHistogram,
        Heatmap heatmap,
        Coverage coverage,
        PathMetrics gazePath,
        GameplayMetrics gameplay) {

    public BaselineStreamSummary {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(therapySessionId, "therapySessionId");
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(missingSequenceRanges, "missingSequenceRanges");
        Objects.requireNonNull(observedTimeRange, "observedTimeRange");
        Objects.requireNonNull(sampleCounts, "sampleCounts");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(confidenceHistogram, "confidenceHistogram");
        Objects.requireNonNull(heatmap, "heatmap");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(gazePath, "gazePath");
        Objects.requireNonNull(gameplay, "gameplay");
        missingSequenceRanges = List.copyOf(missingSequenceRanges);
        confidenceHistogram = List.copyOf(confidenceHistogram);

        require(profile == AnalyticsCalculationProfile.BASELINE_V1, "profile must be baseline-v1");
        require(sourceSchemaVersion == 1, "sourceSchemaVersion must be 1");
        require(minimumSequence >= 0 && maximumSequence >= minimumSequence, "invalid sequence range");
        require(lateArrivalCount >= 0, "lateArrivalCount must be non-negative");
        require(unknownSessionBoundary, "V1 session boundary must remain unknown");
        require(confidenceHistogram.size() == profile.confidenceBinCount(), "invalid confidence histogram size");
        requireNonNegative(confidenceHistogram, "confidenceHistogram");
        require(sum(confidenceHistogram) == sampleCounts.gaze(), "confidence histogram must count all gaze samples");
        require(confidence.count() == sampleCounts.gaze(), "confidence count must match gaze samples");
        require(heatmap.rows() == profile.gridRows() && heatmap.columns() == profile.gridColumns(),
                "heatmap dimensions must match profile");
        require(sum(heatmap.cellCounts()) == sampleCounts.gaze(), "heatmap must count all gaze samples");
        require(coverage.occupiedCellCount() <= (long) profile.gridRows() * profile.gridColumns(),
                "occupied cells exceed grid");
        require((sampleCounts.total() == 0) == observedTimeRange.isEmpty(),
                "observed time range must match sample presence");
        require(gameplay.playerTargetDistance().count() == sampleCounts.gameplay(),
                "gameplay distance count must match gameplay samples");
        require(gameplay.score().distribution().count() == sampleCounts.gameplay(),
                "score count must match gameplay samples");
        require(gameplay.speed().distribution().count() == sampleCounts.gameplay(),
                "speed count must match gameplay samples");
        require(gameplay.progress().distribution().count() == sampleCounts.gameplay(),
                "progress count must match gameplay samples");
    }

    public String calculationVersion() {
        return profile.calculationVersion();
    }

    public long highSequence() {
        return maximumSequence;
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

        public Duration span() {
            return Duration.between(minimum, maximum);
        }
    }

    public record SampleCounts(long gaze, long gameplay) {
        public SampleCounts {
            require(gaze >= 0 && gameplay >= 0, "sample counts must be non-negative");
        }

        public long total() {
            return Math.addExact(gaze, gameplay);
        }
    }

    public record NumericSummary(
            long count,
            OptionalDouble minimum,
            OptionalDouble maximum,
            OptionalDouble mean) {

        public NumericSummary {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            Objects.requireNonNull(mean, "mean");
            require(count >= 0, "count must be non-negative");
            boolean present = minimum.isPresent() && maximum.isPresent() && mean.isPresent();
            require(count == 0 ? !minimum.isPresent() && !maximum.isPresent() && !mean.isPresent() : present,
                    "summary values must be absent only when count is zero");
            if (present) {
                requireFinite(minimum.getAsDouble(), "minimum");
                requireFinite(maximum.getAsDouble(), "maximum");
                requireFinite(mean.getAsDouble(), "mean");
                require(minimum.getAsDouble() <= maximum.getAsDouble(), "minimum exceeds maximum");
                require(mean.getAsDouble() >= minimum.getAsDouble() && mean.getAsDouble() <= maximum.getAsDouble(),
                        "mean is outside range");
            }
        }

        public static NumericSummary empty() {
            return new NumericSummary(0, OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
        }
    }

    public record OrderedNumericSummary(
            NumericSummary distribution,
            OptionalDouble first,
            OptionalDouble last,
            OptionalDouble signedDelta) {

        public OrderedNumericSummary {
            Objects.requireNonNull(distribution, "distribution");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
            Objects.requireNonNull(signedDelta, "signedDelta");
            boolean present = first.isPresent() && last.isPresent() && signedDelta.isPresent();
            require(distribution.count() == 0 ? !first.isPresent() && !last.isPresent() && !signedDelta.isPresent()
                    : present, "ordered values must be absent only when count is zero");
            if (present) {
                requireFinite(first.getAsDouble(), "first");
                requireFinite(last.getAsDouble(), "last");
                requireFinite(signedDelta.getAsDouble(), "signedDelta");
            }
        }

        public static OrderedNumericSummary empty() {
            return new OrderedNumericSummary(NumericSummary.empty(), OptionalDouble.empty(),
                    OptionalDouble.empty(), OptionalDouble.empty());
        }
    }

    public record Heatmap(int rows, int columns, List<Long> cellCounts) {
        public Heatmap {
            Objects.requireNonNull(cellCounts, "cellCounts");
            cellCounts = List.copyOf(cellCounts);
            require(rows > 0 && columns > 0, "heatmap dimensions must be positive");
            require(cellCounts.size() == Math.multiplyExact(rows, columns), "invalid heatmap cell count");
            requireNonNegative(cellCounts, "cellCounts");
        }
    }

    public record BoundingBox(double minimumX, double minimumY, double maximumX, double maximumY,
            double width, double height, double area) {
        public BoundingBox {
            requireFinite(minimumX, "minimumX");
            requireFinite(minimumY, "minimumY");
            requireFinite(maximumX, "maximumX");
            requireFinite(maximumY, "maximumY");
            requireFinite(width, "width");
            requireFinite(height, "height");
            requireFinite(area, "area");
            require(minimumX <= maximumX && minimumY <= maximumY, "invalid bounding box");
            require(width >= 0 && height >= 0 && area >= 0, "negative bounding box measure");
        }
    }

    public record Coverage(Optional<BoundingBox> boundingBox, long occupiedCellCount, double occupiedCellRatio) {
        public Coverage {
            Objects.requireNonNull(boundingBox, "boundingBox");
            require(occupiedCellCount >= 0, "occupiedCellCount must be non-negative");
            requireFinite(occupiedCellRatio, "occupiedCellRatio");
            require(occupiedCellRatio >= 0 && occupiedCellRatio <= 1, "occupiedCellRatio must be normalized");
            require((occupiedCellCount == 0) == boundingBox.isEmpty(),
                    "bounding box presence must match occupied gaze cells");
        }
    }

    public record PathMetrics(
            double totalDistance,
            NumericSummary velocity,
            long excludedNonPositiveDeltaCount) {
        public PathMetrics {
            requireFinite(totalDistance, "totalDistance");
            Objects.requireNonNull(velocity, "velocity");
            require(totalDistance >= 0, "totalDistance must be non-negative");
            require(excludedNonPositiveDeltaCount >= 0, "excluded count must be non-negative");
        }
    }

    public record GameplayMetrics(
            NumericSummary playerTargetDistance,
            OrderedNumericSummary score,
            OrderedNumericSummary speed,
            OrderedNumericSummary progress) {
        public GameplayMetrics {
            Objects.requireNonNull(playerTargetDistance, "playerTargetDistance");
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(speed, "speed");
            Objects.requireNonNull(progress, "progress");
        }
    }

    private static void requireNonNegative(List<Long> values, String name) {
        require(values.stream().allMatch(value -> value != null && value >= 0), name + " must be non-negative");
    }

    private static long sum(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).reduce(0, Math::addExact);
    }

    private static void requireFinite(double value, String name) {
        require(Double.isFinite(value), name + " must be finite");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
