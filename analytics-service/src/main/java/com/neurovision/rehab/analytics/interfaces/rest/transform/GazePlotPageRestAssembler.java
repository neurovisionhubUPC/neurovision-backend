package com.neurovision.rehab.analytics.interfaces.rest.transform;

import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPoint;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Maps only observed source gaze points and bounded projection metadata to the public API. */
public final class GazePlotPageRestAssembler {
    private GazePlotPageRestAssembler() {}

    public static GazePlotPageResponse toResponse(GazePlotPage page, String nextCursorToken) {
        Objects.requireNonNull(page, "page");
        var metadata = page.metadata();
        return new GazePlotPageResponse(1, "OBSERVED_GAZE_SAMPLES", metadata.identity().calculationVersion(),
                metadata.projectionRevision(), new Completeness(metadata.sourceSchemaVersion(),
                        metadata.minimumSequence(), metadata.maximumSequence(), metadata.lateArrivalCount(),
                        metadata.unknownSessionBoundary(), metadata.gazePointCount(),
                        metadata.missingSequenceRanges().stream().map(range ->
                                new MissingSequenceRange(range.fromInclusive(), range.toInclusive())).toList(),
                        metadata.observedTimeRange().map(range ->
                                new ObservedTimeRange(range.minimum(), range.maximum())).orElse(null)),
                page.points().stream().map(GazePlotPageRestAssembler::point).toList(),
                nextCursorToken);
    }

    private static ObservedPoint point(GazePlotPoint point) {
        return new ObservedPoint(point.sequence(), point.gazeSampleIndex(), point.effectiveTime(), point.x(),
                point.y(), point.confidence(), point.segmentBreakBefore());
    }

    public record GazePlotPageResponse(int contractVersion, String pointSemantics, String calculationVersion,
            long projectionRevision, Completeness completeness, List<ObservedPoint> points, String nextCursor) {
        public GazePlotPageResponse {
            Objects.requireNonNull(pointSemantics, "pointSemantics");
            Objects.requireNonNull(calculationVersion, "calculationVersion");
            Objects.requireNonNull(completeness, "completeness");
            points = List.copyOf(points);
        }
    }

    public record Completeness(int sourceSchemaVersion, long minimumSequence, long highSequence,
            long lateArrivalCount, boolean unknownSessionBoundary, long observedGazePointCount,
            List<MissingSequenceRange> missingSequenceRanges, ObservedTimeRange observedTimeRange) {
        public Completeness {
            missingSequenceRanges = List.copyOf(missingSequenceRanges);
        }
    }

    public record MissingSequenceRange(long fromInclusive, long toInclusive) {}
    public record ObservedTimeRange(Instant minimum, Instant maximum) {}
    public record ObservedPoint(long sequence, int gazeSampleIndex, Instant effectiveTime,
            double x, double y, double confidence, boolean segmentBreakBefore) {}
}
