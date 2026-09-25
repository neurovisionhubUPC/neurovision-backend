package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;

/** Resume position and same-lane continuity state for one Replay V1 projection revision. */
public record ReplayCursor(int version, AnalyticsProjectionIdentity identity, ReplayLane lane,
        long projectionRevision, long resumeSequence, int resumeSampleIndex,
        long previousSampleSequence, int previousSampleIndex, Instant previousEffectiveTime) {
    public static final int CURRENT_VERSION = 1;

    public ReplayCursor {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lane, "lane");
        if (version != CURRENT_VERSION || projectionRevision < 1 || resumeSequence < 0
                || resumeSampleIndex < -1 || previousSampleSequence < -1 || previousSampleIndex < -1
                || (previousSampleSequence == -1) != (previousSampleIndex == -1)
                || (previousSampleSequence == -1) != (previousEffectiveTime == null)
                || previousSampleSequence > resumeSequence
                || (previousSampleSequence == resumeSequence && resumeSampleIndex >= 0
                        && previousSampleIndex >= resumeSampleIndex)) {
            throw new IllegalArgumentException("Invalid replay cursor");
        }
    }

    public boolean resumesWithinBatch() { return resumeSampleIndex >= 0; }
}
