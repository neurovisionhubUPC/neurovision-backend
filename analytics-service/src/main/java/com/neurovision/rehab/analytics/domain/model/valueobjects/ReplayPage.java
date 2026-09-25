package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.Objects;

public record ReplayPage(ReplayMetadata metadata, ReplayLanePage gaze, ReplayLanePage gameplay) {
    public ReplayPage {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(gaze);
        Objects.requireNonNull(gameplay);
        if (gaze.lane() != ReplayLane.GAZE || gameplay.lane() != ReplayLane.GAMEPLAY) {
            throw new IllegalArgumentException("Replay lanes are mismatched");
        }
    }
}
