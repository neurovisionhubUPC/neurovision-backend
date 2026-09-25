package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Summary-derived identity and bounded completeness facts for a replay revision. */
public record ReplayMetadata(AnalyticsProjectionIdentity identity, long projectionRevision,
        int sourceSchemaVersion, long minimumSequence, long maximumSequence,
        List<HeatmapProjection.SequenceRange> missingSequenceRanges, boolean unknownSessionBoundary) {
    public ReplayMetadata {
        Objects.requireNonNull(identity, "identity");
        missingSequenceRanges = List.copyOf(Objects.requireNonNull(missingSequenceRanges, "missingSequenceRanges"));
        if (projectionRevision < 1 || sourceSchemaVersion != 1 || minimumSequence < 0
                || maximumSequence < minimumSequence) throw new IllegalArgumentException("Invalid replay metadata");
        long previousEnd = minimumSequence;
        BigInteger missingCount = BigInteger.ZERO;
        for (var range : missingSequenceRanges) {
            if (range.fromInclusive() <= previousEnd || range.toInclusive() >= maximumSequence) {
                throw new IllegalArgumentException("Invalid replay missing-sequence ranges");
            }
            missingCount = missingCount.add(BigInteger.valueOf(range.toInclusive())
                    .subtract(BigInteger.valueOf(range.fromInclusive())).add(BigInteger.ONE));
            previousEnd = range.toInclusive();
        }
        BigInteger observedRange = BigInteger.valueOf(maximumSequence)
                .subtract(BigInteger.valueOf(minimumSequence)).add(BigInteger.ONE);
        if (!observedRange.subtract(missingCount).equals(BigInteger.valueOf(projectionRevision))) {
            throw new IllegalArgumentException("Replay revision does not match observed sequence range and gaps");
        }
    }

    public boolean hasMissingSequenceBetween(long previousSequence, long nextSequence) {
        if (nextSequence <= previousSequence || previousSequence == Long.MAX_VALUE) return false;
        long from = previousSequence + 1;
        long to = nextSequence - 1;
        return missingSequenceRanges.stream().anyMatch(range ->
                range.fromInclusive() <= to && range.toInclusive() >= from);
    }
}
