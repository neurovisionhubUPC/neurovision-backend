package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.List;
import java.util.Optional;

public record ReplayLanePage(ReplayLane lane, List<ReplaySample> samples, Optional<ReplayCursor> nextCursor,
        List<HeatmapProjection.SequenceRange> knownMissingSequenceRanges) {
    public ReplayLanePage {
        samples = List.copyOf(samples);
        nextCursor = java.util.Objects.requireNonNull(nextCursor);
        knownMissingSequenceRanges = List.copyOf(knownMissingSequenceRanges);
    }
}
