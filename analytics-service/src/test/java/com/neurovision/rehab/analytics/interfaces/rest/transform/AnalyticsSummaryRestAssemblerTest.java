package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AnalyticsSummaryRestAssemblerTest {
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STREAM_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void exposesAllRangesWhenCountIsExactlyOneThousand() {
        var response = AnalyticsSummaryRestAssembler.toResponse(projection(1_000));

        assertEquals(1_000, response.completeness().missingSequenceRangeCount());
        assertFalse(response.completeness().missingSequenceRangesTruncated());
        assertEquals(1_000, response.completeness().missingSequenceRanges().size());
    }

    @Test
    void marksTruncatedPreviewWhenCountExceedsOneThousand() {
        var response = AnalyticsSummaryRestAssembler.toResponse(projection(1_001));

        assertEquals(1_001, response.completeness().missingSequenceRangeCount());
        assertTrue(response.completeness().missingSequenceRangesTruncated());
        assertEquals(1_000, response.completeness().missingSequenceRanges().size());
        assertEquals(1, response.completeness().missingSequenceRanges().get(0).fromInclusive());
        assertEquals(1_999, response.completeness().missingSequenceRanges().get(999).toInclusive());
    }

    private static AnalyticsSummaryProjection projection(int missingRangeCount) {
        long maximumSequence = 2L * missingRangeCount;
        var ranges = IntStream.range(0, missingRangeCount)
                .mapToObj(index -> new BaselineStreamSummary.SequenceRange(1L + 2L * index, 1L + 2L * index))
                .toList();
        var summary = new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1, 1, SESSION_ID, STREAM_ID,
                0, maximumSequence, ranges, 0, true, Optional.empty(),
                new BaselineStreamSummary.SampleCounts(0, 0), BaselineStreamSummary.NumericSummary.empty(),
                List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                new BaselineStreamSummary.Heatmap(10, 10, Collections.nCopies(100, 0L)),
                new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0),
                new BaselineStreamSummary.PathMetrics(0, BaselineStreamSummary.NumericSummary.empty(), 0),
                new BaselineStreamSummary.GameplayMetrics(BaselineStreamSummary.NumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty()));
        return new AnalyticsSummaryProjection(new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1"),
                1, summary);
    }
}
