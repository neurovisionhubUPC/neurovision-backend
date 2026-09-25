package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;

/** Bounded, deterministic page of observed points for one projection revision. */
public record GazePlotPage(GazePlotMetadata metadata, List<GazePlotPoint> points,
        Optional<GazePlotCursor> nextCursor) {
    public GazePlotPage {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(points, "points");
        Objects.requireNonNull(nextCursor, "nextCursor");
        points = List.copyOf(points);
        if (points.size() > com.neurovision.rehab.analytics.domain.services.GazePlotPageAssembler.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Gaze-plot page exceeds its maximum size");
        }
        var ordered = points.stream().sorted(Comparator.comparingLong(GazePlotPoint::sequence)
                .thenComparingInt(GazePlotPoint::gazeSampleIndex)).toList();
        if (!ordered.equals(points)) throw new IllegalArgumentException("Gaze-plot points are not source ordered");
        if (nextCursor.isPresent()) {
            var cursor = nextCursor.orElseThrow();
            if (!metadata.identity().equals(cursor.identity())
                    || metadata.projectionRevision() != cursor.projectionRevision()) {
                throw new IllegalArgumentException("Cursor does not match gaze-plot page revision");
            }
            if (!points.isEmpty()) {
                var last = points.getLast();
                if (cursor.previousPointSequence() != last.sequence()
                        || cursor.previousPointGazeSampleIndex() != last.gazeSampleIndex()
                        || !last.effectiveTime().equals(cursor.previousEffectiveTime())) {
                    throw new IllegalArgumentException("Cursor does not continue the final page point");
                }
            }
        }
    }
}
