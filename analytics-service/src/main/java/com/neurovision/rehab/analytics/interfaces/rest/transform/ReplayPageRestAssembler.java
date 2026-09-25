package com.neurovision.rehab.analytics.interfaces.rest.transform;

import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLanePage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayPage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Maps Replay V1 observed source samples to a versioned HTTP envelope. */
public final class ReplayPageRestAssembler {
    private ReplayPageRestAssembler() {}

    public static ReplayResponse toResponse(ReplayPage page, String nextGazeCursor, String nextGameplayCursor) {
        Objects.requireNonNull(page);
        var metadata = page.metadata();
        return new ReplayResponse(1, "admitted-telemetry-kafka-v1", metadata.identity().therapySessionId(),
                metadata.identity().streamId(), metadata.projectionRevision(), metadata.sourceSchemaVersion(),
                metadata.minimumSequence(), metadata.maximumSequence(), metadata.unknownSessionBoundary(),
                gazeLane(page.gaze(), nextGazeCursor), gameplayLane(page.gameplay(), nextGameplayCursor));
    }

    private static GazeLaneResponse gazeLane(ReplayLanePage lane, String nextCursor) {
        return new GazeLaneResponse(lane.samples().stream().map(sample -> new GazeObservedSample(sample.batchId(),
                sample.sequence(), sample.sourceArrayIndex(), sample.capturedAt(), sample.offsetMs(),
                sample.effectiveTime(), sample.x(), sample.y(), sample.confidence(), sample.segmentBreakBefore())).toList(),
                nextCursor, lane.knownMissingSequenceRanges().stream().map(ReplayPageRestAssembler::range).toList());
    }

    private static GameplayLaneResponse gameplayLane(ReplayLanePage lane, String nextCursor) {
        return new GameplayLaneResponse(lane.samples().stream().map(sample -> new GameplayObservedSample(sample.batchId(),
                sample.sequence(), sample.sourceArrayIndex(), sample.capturedAt(), sample.offsetMs(),
                sample.effectiveTime(), sample.playerX(), sample.playerY(), sample.targetX(), sample.targetY(),
                sample.score(), sample.speed(), sample.progress(), sample.segmentBreakBefore())).toList(), nextCursor,
                lane.knownMissingSequenceRanges().stream().map(ReplayPageRestAssembler::range).toList());
    }

    private static MissingSequenceRange range(HeatmapProjection.SequenceRange range) {
        return new MissingSequenceRange(range.fromInclusive(), range.toInclusive());
    }

    public record ReplayResponse(int contractVersion, String source, java.util.UUID therapySessionId,
            java.util.UUID streamId, long projectionRevision, int sourceSchemaVersion, long minimumSequence,
            long maximumSequence, boolean unknownSessionBoundary,
            GazeLaneResponse gazeSamples, GameplayLaneResponse gameplaySamples) {
    }
    public record GazeLaneResponse(List<GazeObservedSample> samples, String nextCursor,
            List<MissingSequenceRange> knownMissingSequenceRanges) {
        public GazeLaneResponse { samples = List.copyOf(samples); knownMissingSequenceRanges = List.copyOf(knownMissingSequenceRanges); }
    }
    public record GameplayLaneResponse(List<GameplayObservedSample> samples, String nextCursor,
            List<MissingSequenceRange> knownMissingSequenceRanges) {
        public GameplayLaneResponse { samples = List.copyOf(samples); knownMissingSequenceRanges = List.copyOf(knownMissingSequenceRanges); }
    }
    public record GazeObservedSample(java.util.UUID batchId, long sequence, int sourceArrayIndex, Instant capturedAt,
            long offsetMs, Instant effectiveTime, Double x, Double y, Double confidence, boolean segmentBreakBefore) {}
    public record GameplayObservedSample(java.util.UUID batchId, long sequence, int sourceArrayIndex, Instant capturedAt,
            long offsetMs, Instant effectiveTime, Double playerX, Double playerY, Double targetX, Double targetY,
            Long score, Double speed, Double progress, boolean segmentBreakBefore) {}
    public record MissingSequenceRange(long fromInclusive, long toInclusive) {}
}
