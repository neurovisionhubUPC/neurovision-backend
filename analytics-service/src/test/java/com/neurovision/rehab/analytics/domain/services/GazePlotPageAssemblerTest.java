package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazeSourceBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GazePlotPageAssemblerTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AnalyticsProjectionIdentity IDENTITY =
            new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");

    @Test
    void emitsEveryPointInSequenceThenArrayOrderAndBreaksAtMissingSequenceAndBackwardTime() {
        var metadata = metadata(4, 1, 4, List.of(new HeatmapProjection.SequenceRange(2, 2)), 3);
        var page = GazePlotPageAssembler.page(metadata, List.of(
                batch(3, "2026-09-24T10:00:00Z", point(0, .3, .4, .8)),
                batch(1, "2026-09-24T10:00:00Z", point(0, .1, .2, .6), point(10, .2, .3, .7)),
                batch(4, "2026-09-24T09:59:59Z", point(0, .4, .5, .9))), null, 10);

        assertEquals(List.of(1L, 1L, 3L, 4L), page.points().stream().map(p -> p.sequence()).toList());
        assertEquals(List.of(0, 1, 0, 0), page.points().stream().map(p -> p.gazeSampleIndex()).toList());
        assertEquals(List.of(true, false, true, true), page.points().stream()
                .map(p -> p.segmentBreakBefore()).toList());
        assertEquals(List.of(.6, .7, .8, .9), page.points().stream().map(p -> p.confidence()).toList());
        assertTrue(page.nextCursor().isEmpty());
    }

    @Test
    void continuesWithinBatchAndPreservesBoundaryTimeForNextPage() {
        var metadata = metadata(2, 1, 2, List.of(), 3);
        var firstBatch = batch(1, "2026-09-24T10:00:00Z",
                point(10, .1, .2, .8), point(20, .2, .3, .7));
        var secondBatch = batch(2, "2026-09-24T09:59:59Z", point(5, .3, .4, .6));
        var first = GazePlotPageAssembler.page(metadata, List.of(firstBatch, secondBatch), null, 2);
        var cursor = first.nextCursor().orElseThrow();
        var second = GazePlotPageAssembler.page(metadata, List.of(secondBatch), cursor, 2);

        assertEquals(2, first.points().size());
        assertEquals(2, cursor.resumeSequence());
        assertEquals(0, cursor.resumeGazeSampleIndex());
        assertEquals(1, second.points().size());
        assertTrue(second.points().getFirst().segmentBreakBefore());
        assertEquals(Instant.parse("2026-09-24T09:59:59.005Z"), second.points().getFirst().effectiveTime());
        assertTrue(second.nextCursor().isEmpty());
    }

    @Test
    void doesNotTreatAnAdmittedEmptyBatchAsAMissingSequence() {
        var metadata = metadata(3, 1, 3, List.of(), 2);
        var page = GazePlotPageAssembler.page(metadata, List.of(
                batch(1, "2026-09-24T10:00:00Z", point(0, .1, .2, .8)),
                batch(2, "2026-09-24T10:00:00Z"),
                batch(3, "2026-09-24T10:00:00Z", point(1, .2, .3, .9))), null, 10);

        assertEquals(List.of(true, false), page.points().stream().map(p -> p.segmentBreakBefore()).toList());
    }

    @Test
    void rejectsCursorFromAnotherIdentityOrRevisionAndMalformedInboxWindows() {
        var metadata = metadata(2, 1, 1, List.of(), 2);
        var cursor = new GazePlotCursor(2, IDENTITY, 1, 1, 1, 0, 1,
                Instant.parse("2026-09-24T10:00:00Z"));
        assertThrows(IllegalArgumentException.class,
                () -> GazePlotPageAssembler.page(metadata, List.of(), cursor, 1));
        assertThrows(IllegalArgumentException.class,
                () -> GazePlotPageAssembler.page(metadata, List.of(batch(2, "2026-09-24T10:00:00Z",
                        point(0, .2, .3, .7))), null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GazePlotCursor(3, IDENTITY, 1, 1, 0, -1, -1, null));
        assertFalse(new GazePlotCursor(2, IDENTITY, 1, 1, -1, -1, -1, null).resumesWithinBatch());
    }

    @Test
    void failsClosedWhenInboxSequenceGapsDisagreeWithSummaryOrCursorBatchIsGone() {
        var unmarkedGap = metadata(2, 1, 3, List.of(), 2);
        assertThrows(IllegalArgumentException.class, () -> GazePlotPageAssembler.page(unmarkedGap, List.of(
                batch(1, "2026-09-24T10:00:00Z", point(0, .1, .2, .8)),
                batch(3, "2026-09-24T10:00:00Z", point(0, .2, .3, .9))), null, 10));

        var metadata = metadata(2, 1, 3, List.of(new HeatmapProjection.SequenceRange(2, 2)), 2);
        var cursor = new GazePlotCursor(2, IDENTITY, 2, 1, 1, 1, 0,
                Instant.parse("2026-09-24T10:00:00Z"));
        assertThrows(IllegalArgumentException.class,
                () -> GazePlotPageAssembler.page(metadata, List.of(batch(3, "2026-09-24T10:00:00Z",
                        point(0, .2, .3, .9))), cursor, 10));
    }

    @Test
    void capsBatchWindowsAndPageSize() {
        var metadata = metadata(1, 0, GazePlotPageAssembler.MAX_BATCH_SCAN,
                List.of(), 0);
        assertThrows(IllegalArgumentException.class,
                () -> GazePlotPageAssembler.page(metadata, List.of(), null, GazePlotPageAssembler.MAX_PAGE_SIZE + 1));
        var tooManyBatches = java.util.stream.LongStream.range(0, GazePlotPageAssembler.MAX_BATCH_SCAN + 1)
                .mapToObj(sequence -> batch(sequence, "2026-09-24T10:00:00Z"))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> GazePlotPageAssembler.page(metadata, tooManyBatches, null, 10));
    }

    private static GazePlotMetadata metadata(long revision, long minimum, long maximum,
            List<HeatmapProjection.SequenceRange> missing, long gazeCount) {
        var time = gazeCount == 0 ? Optional.<GazePlotMetadata.TimeRange>empty()
                : Optional.of(new GazePlotMetadata.TimeRange(
                        Instant.parse("2026-09-24T09:00:00Z"), Instant.parse("2026-09-24T11:00:00Z")));
        return new GazePlotMetadata(IDENTITY, revision, 1, minimum, maximum, maximum,
                missing, 0, true, gazeCount, time);
    }

    private static GazeSourceBatch.Sample point(long offset, double x, double y, double confidence) {
        return new GazeSourceBatch.Sample(offset, x, y, confidence);
    }

    private static GazeSourceBatch batch(long sequence, String capturedAt,
            GazeSourceBatch.Sample... gaze) {
        var instant = Instant.parse(capturedAt);
        return new GazeSourceBatch(1, SESSION, STREAM, sequence, instant, List.of(gaze));
    }
}
