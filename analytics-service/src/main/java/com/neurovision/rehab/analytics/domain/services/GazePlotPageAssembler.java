package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPoint;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazeSourceBatch;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds deterministic pages from a bounded, sequence-ordered inbox window. */
public final class GazePlotPageAssembler {
    public static final int MAX_PAGE_SIZE = 500;
    public static final int MAX_BATCH_SCAN = 64;

    private GazePlotPageAssembler() {}

    public static GazePlotPage page(GazePlotMetadata metadata, List<GazeSourceBatch> batches,
            GazePlotCursor cursor, int pageSize) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(batches, "batches");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || batches.size() > MAX_BATCH_SCAN
                || batches.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid gaze-plot page bounds");
        }
        validateCursor(metadata, cursor);
        var orderedBatches = batches.stream().sorted(Comparator.comparingLong(GazeSourceBatch::sequence)).toList();
        validateBatches(metadata, orderedBatches, cursor);

        var points = new ArrayList<GazePlotPoint>(pageSize);
        long previousSequence = cursor == null ? -1 : cursor.previousPointSequence();
        int previousSampleIndex = cursor == null ? -1 : cursor.previousPointGazeSampleIndex();
        var previousTime = cursor == null ? null : cursor.previousEffectiveTime();
        GazePlotCursor continuation = null;
        boolean hasUnscannedBatches = orderedBatches.size() == MAX_BATCH_SCAN
                && orderedBatches.getLast().sequence() < metadata.maximumSequence();

        for (int batchIndex = 0; batchIndex < orderedBatches.size(); batchIndex++) {
            var batch = orderedBatches.get(batchIndex);
            int firstSampleIndex = batchIndex == 0 && cursor != null && cursor.resumesWithinBatch()
                    ? cursor.resumeGazeSampleIndex() : 0;
            if (firstSampleIndex > batch.gazeSamples().size()) {
                throw new IllegalArgumentException("Cursor sample index exceeds source batch");
            }
            for (int sampleIndex = firstSampleIndex; sampleIndex < batch.gazeSamples().size(); sampleIndex++) {
                var sample = batch.gazeSamples().get(sampleIndex);
                var effectiveTime = effectiveTime(batch.capturedAt(), sample.offsetMs());
                if (points.size() == pageSize) {
                    continuation = cursor(metadata, batch.sequence(), sampleIndex,
                            previousSequence, previousSampleIndex, previousTime);
                    break;
                }
                boolean segmentBreak = previousSequence < 0
                        || metadata.hasMissingSequenceBetween(previousSequence, batch.sequence())
                        || previousTime != null && effectiveTime.isBefore(previousTime);
                points.add(new GazePlotPoint(batch.sequence(), sampleIndex, effectiveTime,
                        sample.x(), sample.y(), sample.confidence(), segmentBreak));
                previousSequence = batch.sequence();
                previousSampleIndex = sampleIndex;
                previousTime = effectiveTime;
            }
            if (continuation != null) break;
        }

        if (continuation == null && hasUnscannedBatches) {
            var lastScanned = orderedBatches.getLast();
            continuation = cursor(metadata, lastScanned.sequence(), -1, previousSequence,
                    previousSampleIndex, previousTime);
        }
        return new GazePlotPage(metadata, points, Optional.ofNullable(continuation));
    }

    private static GazePlotCursor cursor(GazePlotMetadata metadata, long sequence, int index,
            long previousSequence, int previousSampleIndex, java.time.Instant previousTime) {
        return new GazePlotCursor(GazePlotCursor.CURRENT_VERSION, metadata.identity(),
                metadata.projectionRevision(), sequence, index, previousSequence, previousSampleIndex, previousTime);
    }

    private static void validateCursor(GazePlotMetadata metadata, GazePlotCursor cursor) {
        if (cursor != null && (!metadata.identity().equals(cursor.identity())
                || metadata.projectionRevision() != cursor.projectionRevision()
                || cursor.resumeSequence() > metadata.maximumSequence()
                || cursor.previousPointSequence() > metadata.maximumSequence())) {
            throw new IllegalArgumentException("Stale or mismatched gaze-plot cursor");
        }
    }

    private static void validateBatches(GazePlotMetadata metadata, List<GazeSourceBatch> batches,
            GazePlotCursor cursor) {
        long previous = cursor == null ? -1 : cursor.resumesWithinBatch()
                ? cursor.resumeSequence() - 1 : cursor.resumeSequence();
        if (cursor == null && !batches.isEmpty() && batches.getFirst().sequence() != metadata.minimumSequence()) {
            throw new IllegalArgumentException("Gaze inbox window omits the first observed sequence");
        }
        if (cursor != null && cursor.resumesWithinBatch()
                && (batches.isEmpty() || batches.getFirst().sequence() != cursor.resumeSequence())) {
            throw new IllegalArgumentException("Cursor batch is absent from the gaze inbox window");
        }
        for (var batch : batches) {
            if (batch.schemaVersion() != metadata.sourceSchemaVersion()
                    || !batch.therapySessionId().equals(metadata.identity().therapySessionId())
                    || !batch.streamId().equals(metadata.identity().streamId())
                    || batch.sequence() < metadata.minimumSequence()
                    || batch.sequence() > metadata.maximumSequence()
                    || batch.sequence() <= previous) {
                throw new IllegalArgumentException("Invalid or unordered gaze-plot inbox window");
            }
            if (cursor != null && (batch.sequence() < cursor.resumeSequence()
                    || batch.sequence() == cursor.resumeSequence() && !cursor.resumesWithinBatch())) {
                throw new IllegalArgumentException("Inbox window does not resume after cursor");
            }
            if ((cursor != null || previous >= 0)
                    && !metadata.fullyExplainsSequenceGap(previous, batch.sequence())) {
                throw new IllegalArgumentException("Gaze inbox window has an unreported sequence gap");
            }
            previous = batch.sequence();
        }
        if (batches.size() < MAX_BATCH_SCAN && previous < metadata.maximumSequence()) {
            throw new IllegalArgumentException("Gaze inbox window ends before the summarized maximum sequence");
        }
    }

    private static java.time.Instant effectiveTime(java.time.Instant capturedAt, long offsetMs) {
        try {
            if (offsetMs < 0) throw new IllegalArgumentException("Invalid gaze offset");
            return capturedAt.plusMillis(offsetMs);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid gaze effective time", invalid);
        }
    }
}
