package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;

/** Resume position for one immutable gaze-plot projection revision. */
public record GazePlotCursor(
        int cursorVersion,
        AnalyticsProjectionIdentity identity,
        long projectionRevision,
        long resumeSequence,
        int resumeGazeSampleIndex,
        long previousPointSequence,
        int previousPointGazeSampleIndex,
        Instant previousEffectiveTime) {
    public static final int CURRENT_VERSION = 2;

    public GazePlotCursor {
        Objects.requireNonNull(identity, "identity");
        if (cursorVersion != CURRENT_VERSION || projectionRevision < 1 || resumeSequence < 0
                || resumeGazeSampleIndex < -1 || previousPointSequence < -1
                || previousPointGazeSampleIndex < -1
                || (previousPointSequence == -1) != (previousPointGazeSampleIndex == -1)
                || (previousPointSequence == -1) != (previousEffectiveTime == null)
                || previousPointSequence > resumeSequence
                || (previousPointSequence == resumeSequence
                        && resumeGazeSampleIndex >= 0
                        && previousPointGazeSampleIndex >= resumeGazeSampleIndex)) {
            throw new IllegalArgumentException("Invalid gaze-plot cursor");
        }
    }

    public boolean resumesWithinBatch() {
        return resumeGazeSampleIndex >= 0;
    }
}
