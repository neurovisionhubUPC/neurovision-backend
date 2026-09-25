package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLanePage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplaySample;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayProjectionInconsistentException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplaySourceBatch;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Produces bounded, source-ordered pages without deriving state absent from the source. */
public final class ReplayPageAssembler {
    public static final int MAX_PAGE_SIZE = 500;
    public static final int MAX_BATCH_SCAN = 64;

    private ReplayPageAssembler() {}

    public static ReplayLanePage page(ReplayMetadata metadata, ReplayLane lane,
            List<ReplaySourceBatch> batches, ReplayCursor cursor, int pageSize) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(lane);
        Objects.requireNonNull(batches);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || batches.size() > MAX_BATCH_SCAN
                || batches.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid replay page bounds");
        validateCursor(metadata, lane, cursor);
        var ordered = batches.stream().sorted(Comparator.comparingLong(ReplaySourceBatch::sequence)).toList();
        long previousBatch = cursor == null ? metadata.minimumSequence() - 1
                : cursor.resumesWithinBatch() ? cursor.resumeSequence() - 1 : cursor.resumeSequence();
        if (cursor == null && !ordered.isEmpty() && ordered.getFirst().sequence() != metadata.minimumSequence()) {
            throw new IllegalArgumentException("Replay window omits the first observed sequence");
        }
        if (cursor != null && cursor.resumesWithinBatch()
                && (ordered.isEmpty() || ordered.getFirst().sequence() != cursor.resumeSequence())) {
            throw new IllegalArgumentException("Replay cursor batch is absent");
        }
        for (var batch : ordered) {
            if (metadata.missingSequenceRanges().stream().anyMatch(range ->
                    batch.sequence() >= range.fromInclusive() && batch.sequence() <= range.toInclusive())) {
                throw new ReplayProjectionInconsistentException();
            }
            if (batch.schemaVersion() != metadata.sourceSchemaVersion()
                    || !batch.therapySessionId().equals(metadata.identity().therapySessionId())
                    || !batch.streamId().equals(metadata.identity().streamId())
                    || batch.sequence() < metadata.minimumSequence() || batch.sequence() > metadata.maximumSequence()
                    || batch.sequence() <= previousBatch) throw new IllegalArgumentException("Invalid replay inbox window");
            if (cursor != null && (batch.sequence() < cursor.resumeSequence()
                    || batch.sequence() == cursor.resumeSequence() && !cursor.resumesWithinBatch())) {
                throw new IllegalArgumentException("Replay window does not resume after cursor");
            }
            if (previousBatch >= 0 && !fullyExplainsGap(metadata, previousBatch, batch.sequence())) {
                throw new IllegalArgumentException("Replay inbox window has an unreported sequence gap");
            }
            previousBatch = batch.sequence();
        }

        var result = new ArrayList<ReplaySample>(pageSize);
        long previousSequence = cursor == null ? -1 : cursor.previousSampleSequence();
        int previousIndex = cursor == null ? -1 : cursor.previousSampleIndex();
        var previousTime = cursor == null ? null : cursor.previousEffectiveTime();
        ReplayCursor continuation = null;
        for (int batchIndex = 0; batchIndex < ordered.size(); batchIndex++) {
            ReplaySourceBatch batch = ordered.get(batchIndex);
            int resumeIndex = batchIndex == 0 && cursor != null && cursor.resumesWithinBatch()
                    ? cursor.resumeSampleIndex() : 0;
            int size = lane == ReplayLane.GAZE ? batch.gazeSamples().size() : batch.gameplaySamples().size();
            if (resumeIndex > size) throw new IllegalArgumentException("Replay cursor index exceeds source batch");
            for (int index = resumeIndex; index < size; index++) {
                if (result.size() == pageSize) {
                    continuation = cursor(metadata, lane, batch.sequence(), index,
                            previousSequence, previousIndex, previousTime);
                    break;
                }
                long offset = lane == ReplayLane.GAZE ? batch.gazeSamples().get(index).offsetMs()
                        : batch.gameplaySamples().get(index).offsetMs();
                final java.time.Instant effective;
                try { effective = batch.capturedAt().plusMillis(offset); }
                catch (DateTimeException | ArithmeticException invalid) {
                    throw new IllegalArgumentException("Invalid replay effective time", invalid);
                }
                boolean gap = previousSequence >= 0 && metadata.hasMissingSequenceBetween(previousSequence, batch.sequence());
                boolean backwards = previousTime != null && effective.isBefore(previousTime);
                result.add(sample(batch, lane, index, offset, effective, gap || backwards));
                previousSequence = batch.sequence();
                previousIndex = index;
                previousTime = effective;
            }
            if (continuation != null) break;
        }
        if (continuation == null && !ordered.isEmpty() && ordered.size() == MAX_BATCH_SCAN
                && ordered.getLast().sequence() < metadata.maximumSequence()) {
            continuation = cursor(metadata, lane, ordered.getLast().sequence(), -1,
                    previousSequence, previousIndex, previousTime);
        }
        long first = cursor == null ? metadata.minimumSequence() : cursor.resumeSequence();
        long last = ordered.isEmpty() ? first : ordered.getLast().sequence();
        List<HeatmapProjection.SequenceRange> gaps = metadata.missingSequenceRanges().stream()
                .filter(range -> range.toInclusive() >= first && range.fromInclusive() <= last)
                .map(range -> new HeatmapProjection.SequenceRange(Math.max(first, range.fromInclusive()),
                        Math.min(last, range.toInclusive())))
                .toList();
        return new ReplayLanePage(lane, result, Optional.ofNullable(continuation), gaps);
    }

    private static ReplaySample sample(ReplaySourceBatch batch, ReplayLane lane, int index,
            long offset, java.time.Instant effective, boolean segmentBreak) {
        if (lane == ReplayLane.GAZE) {
            var sample = batch.gazeSamples().get(index);
            return new ReplaySample(lane, batch.batchId(), batch.sequence(), index, batch.capturedAt(), offset,
                    effective, sample.x(), sample.y(), sample.confidence(), null, null, null, null, null, null, null,
                    segmentBreak);
        }
        var sample = batch.gameplaySamples().get(index);
        return new ReplaySample(lane, batch.batchId(), batch.sequence(), index, batch.capturedAt(), offset,
                effective, null, null, null, sample.playerX(), sample.playerY(), sample.targetX(), sample.targetY(),
                sample.score(), sample.speed(), sample.progress(), segmentBreak);
    }

    private static ReplayCursor cursor(ReplayMetadata metadata, ReplayLane lane, long sequence, int index,
            long previousSequence, int previousIndex, java.time.Instant previousTime) {
        return new ReplayCursor(ReplayCursor.CURRENT_VERSION, metadata.identity(), lane,
                metadata.projectionRevision(), sequence, index, previousSequence, previousIndex, previousTime);
    }

    private static void validateCursor(ReplayMetadata metadata, ReplayLane lane, ReplayCursor cursor) {
        if (cursor != null && (!metadata.identity().equals(cursor.identity()) || cursor.lane() != lane
                || metadata.projectionRevision() != cursor.projectionRevision()
                || cursor.resumeSequence() > metadata.maximumSequence()
                || cursor.previousSampleSequence() > metadata.maximumSequence())) {
            throw new IllegalArgumentException("Stale or mismatched replay cursor");
        }
    }

    private static boolean fullyExplainsGap(ReplayMetadata metadata, long previousSequence, long nextSequence) {
        if (nextSequence <= previousSequence || nextSequence - previousSequence <= 1) return true;
        long expected = previousSequence + 1;
        long lastMissing = nextSequence - 1;
        for (var range : metadata.missingSequenceRanges()) {
            if (range.toInclusive() < expected) continue;
            if (range.fromInclusive() > expected) return false;
            if (range.toInclusive() >= lastMissing) return true;
            expected = range.toInclusive() + 1;
        }
        return false;
    }
}
