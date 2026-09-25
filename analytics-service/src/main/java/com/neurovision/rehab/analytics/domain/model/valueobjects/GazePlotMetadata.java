package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Strict summary metadata bound to the current stream-summary revision. */
public record GazePlotMetadata(
        AnalyticsProjectionIdentity identity,
        long projectionRevision,
        int sourceSchemaVersion,
        long minimumSequence,
        long maximumSequence,
        long highSequence,
        List<HeatmapProjection.SequenceRange> missingSequenceRanges,
        long lateArrivalCount,
        boolean unknownSessionBoundary,
        long gazePointCount,
        Optional<TimeRange> observedTimeRange) {
    public GazePlotMetadata {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(missingSequenceRanges, "missingSequenceRanges");
        Objects.requireNonNull(observedTimeRange, "observedTimeRange");
        missingSequenceRanges = List.copyOf(missingSequenceRanges);
        if (projectionRevision < 1 || sourceSchemaVersion != 1 || minimumSequence < 0
                || maximumSequence < minimumSequence || highSequence != maximumSequence
                || lateArrivalCount < 0 || gazePointCount < 0) {
            throw new IllegalArgumentException("Invalid gaze-plot summary metadata");
        }
        long previousEnd = minimumSequence - 1;
        for (var range : missingSequenceRanges) {
            if (range.fromInclusive() <= previousEnd || range.toInclusive() > maximumSequence) {
                throw new IllegalArgumentException("Invalid gaze-plot missing-sequence ranges");
            }
            previousEnd = range.toInclusive();
        }
    }

    public boolean hasMissingSequenceBetween(long previousSequence, long nextSequence) {
        if (nextSequence <= previousSequence || nextSequence - previousSequence <= 1) return false;
        long from = previousSequence + 1;
        long to = nextSequence - 1;
        return missingSequenceRanges.stream().anyMatch(range ->
                range.fromInclusive() <= to && range.toInclusive() >= from);
    }

    public boolean fullyExplainsSequenceGap(long previousSequence, long nextSequence) {
        if (nextSequence <= previousSequence || nextSequence - previousSequence <= 1) return true;
        long expected = previousSequence + 1;
        long finalMissing = nextSequence - 1;
        for (var range : missingSequenceRanges) {
            if (range.toInclusive() < expected) continue;
            if (range.fromInclusive() > expected) return false;
            if (range.toInclusive() >= finalMissing) return true;
            expected = range.toInclusive() + 1;
        }
        return false;
    }

    public record TimeRange(Instant minimum, Instant maximum) {
        public TimeRange {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            if (maximum.isBefore(minimum)) throw new IllegalArgumentException("Invalid observed time range");
        }
    }
}
