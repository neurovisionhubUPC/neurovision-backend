package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayProjectionInconsistentException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplaySourceBatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayPageAssemblerTest {
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STREAM = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CAPTURED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void preservesArrayOrderAndMarksOnlyObservedGapOrBackwardTime() {
        var metadata = metadata(3, 6, List.of(new HeatmapProjection.SequenceRange(4, 4)));
        var batches = List.of(batch(3, List.of(gaze(100), gaze(100)), List.of(game(100))),
                batch(5, List.of(gaze(10)), List.of()), batch(6, List.of(), List.of(game(10))));

        var gaze = ReplayPageAssembler.page(metadata, ReplayLane.GAZE, batches, null, 500);
        var gameplay = ReplayPageAssembler.page(metadata, ReplayLane.GAMEPLAY, batches, null, 500);

        assertEquals(List.of(0, 1, 0), gaze.samples().stream().map(s -> s.sourceArrayIndex()).toList());
        assertEquals(List.of(false, false, true), gaze.samples().stream().map(s -> s.segmentBreakBefore()).toList());
        assertEquals(CAPTURED.plusMillis(100), gaze.samples().get(0).effectiveTime());
        assertEquals(List.of(false, true), gameplay.samples().stream().map(s -> s.segmentBreakBefore()).toList());
        assertEquals(List.of(100L, 10L), gameplay.samples().stream().map(s -> s.offsetMs()).toList());
        assertEquals(List.of(new HeatmapProjection.SequenceRange(4, 4)), gaze.knownMissingSequenceRanges());
    }

    @Test
    void emptyLaneBatchDoesNotCreateGapOrBreakAndPagesIndependently() {
        var metadata = metadata(3, 5, List.of());
        var batches = List.of(batch(3, List.of(gaze(1), gaze(2)), List.of()),
                batch(4, List.of(), List.of()), batch(5, List.of(gaze(3)), List.of()));

        var first = ReplayPageAssembler.page(metadata, ReplayLane.GAZE, batches, null, 1);
        assertTrue(first.nextCursor().isPresent());
        var cursor = first.nextCursor().orElseThrow();
        var secondWindow = batches.stream().filter(b -> b.sequence() >= cursor.resumeSequence()).toList();
        var second = ReplayPageAssembler.page(metadata, ReplayLane.GAZE, secondWindow, cursor, 1);
        assertEquals(1, first.samples().size());
        assertEquals(1, second.samples().size());
        assertFalse(second.samples().getFirst().segmentBreakBefore());
        assertEquals(1, second.samples().getFirst().sourceArrayIndex());
    }

    @Test
    void rejectsUnreportedMissingBatchAndOverBoundWindow() {
        var metadata = metadata(3, 70, List.of());
        assertThrows(IllegalArgumentException.class, () -> ReplayPageAssembler.page(metadata, ReplayLane.GAZE,
                List.of(batch(3, List.of(gaze(1)), List.of()), batch(5, List.of(gaze(2)), List.of())), null, 20));
        assertThrows(IllegalArgumentException.class, () -> ReplayPageAssembler.page(metadata, ReplayLane.GAZE,
                java.util.stream.LongStream.range(3, 68).mapToObj(s -> batch(s, List.of(), List.of())).toList(), null, 20));
    }

    @Test
    void failsClosedWhenInboxContainsASequenceStillDeclaredMissingBySummary() {
        var metadata = metadata(3, 6, List.of(new HeatmapProjection.SequenceRange(4, 4)));
        var contradictory = List.of(batch(3, List.of(gaze(1)), List.of()),
                batch(4, List.of(gaze(2)), List.of()), batch(5, List.of(gaze(3)), List.of()),
                batch(6, List.of(gaze(4)), List.of()));

        assertThrows(ReplayProjectionInconsistentException.class,
                () -> ReplayPageAssembler.page(metadata, ReplayLane.GAZE, contradictory, null, 20));
    }

    private static ReplayMetadata metadata(long min, long max, List<HeatmapProjection.SequenceRange> gaps) {
        long missingCount = gaps.stream().mapToLong(range -> range.toInclusive() - range.fromInclusive() + 1).sum();
        return new ReplayMetadata(new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1"),
                max - min + 1 - missingCount, 1, min, max, gaps, true);
    }
    private static ReplaySourceBatch batch(long sequence, List<ReplaySourceBatch.GazeSample> gaze,
            List<ReplaySourceBatch.GameplaySample> gameplay) {
        return new ReplaySourceBatch(1, UUID.nameUUIDFromBytes(Long.toString(sequence).getBytes()), SESSION, STREAM,
                sequence, CAPTURED, gaze, gameplay);
    }
    private static ReplaySourceBatch.GazeSample gaze(long offset) { return new ReplaySourceBatch.GazeSample(offset, .2, .3, .9); }
    private static ReplaySourceBatch.GameplaySample game(long offset) {
        return new ReplaySourceBatch.GameplaySample(offset, .1, .2, .3, .4, 1, 1.5, .6);
    }
}
