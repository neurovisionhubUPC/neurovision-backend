package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoAnalyticsSummaryQueryAdapterTest {
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STREAM_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void reconstructsExistingPersistedSummaryWithoutRecalculatingMetrics() {
        var source = summary();
        var identity = new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1");
        var document = new Document("therapySessionId", SESSION_ID.toString())
                .append("streamId", STREAM_ID.toString())
                .append("calculationVersion", "baseline-v1")
                .append("admittedBatchCount", 3L)
                .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(source));

        var result = MongoAnalyticsSummaryQueryAdapter.reconstruct(document, identity);

        assertEquals(identity, result.identity());
        assertEquals(3, result.revision());
        assertEquals(source, result.summary());
    }

    @Test
    void acceptsExactlyOneThousandValidMissingSequenceRanges() {
        var projection = MongoAnalyticsSummaryQueryAdapter.reconstruct(
                document(summary(1_000)), new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1"));

        assertEquals(1_000, projection.summary().missingSequenceRanges().size());
    }

    @Test
    void acceptsMoreThanOneThousandValidMissingSequenceRangesForBoundedApiPreview() {
        var projection = MongoAnalyticsSummaryQueryAdapter.reconstruct(
                document(summary(1_001)), new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1"));

        assertEquals(1_001, projection.summary().missingSequenceRanges().size());
    }

    @Test
    void rejectsPersistedHighSequenceThatDiffersFromMaximumSequence() {
        var corrupt = MongoStreamSummaryProjectionAdapter.summaryDocument(summary());
        corrupt.put("highSequence", 1L);

        assertThrows(IllegalArgumentException.class, () -> MongoAnalyticsSummaryQueryAdapter.reconstruct(
                document(corrupt), new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1")));
    }

    @Test
    void rejectsMissingSequenceRangesOutsideBoundsOrOutOfOrder() {
        var corrupt = MongoStreamSummaryProjectionAdapter.summaryDocument(summary());
        corrupt.put("maximumSequence", 5L);
        corrupt.put("highSequence", 5L);
        corrupt.put("missingSequenceRanges", List.of(
                new Document("fromInclusive", 3L).append("toInclusive", 3L),
                new Document("fromInclusive", 1L).append("toInclusive", 1L)));

        assertThrows(IllegalArgumentException.class, () -> MongoAnalyticsSummaryQueryAdapter.reconstruct(
                document(corrupt), new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1")));
    }

    @Test
    void rejectsMissingSequenceRangeThatTouchesObservedBoundary() {
        var corrupt = MongoStreamSummaryProjectionAdapter.summaryDocument(summary());
        corrupt.put("minimumSequence", 0L);
        corrupt.put("maximumSequence", 4L);
        corrupt.put("highSequence", 4L);
        corrupt.put("missingSequenceRanges", List.of(
                new Document("fromInclusive", 0L).append("toInclusive", 1L)));

        assertThrows(IllegalArgumentException.class, () -> MongoAnalyticsSummaryQueryAdapter.reconstruct(
                document(corrupt), new AnalyticsProjectionIdentity(SESSION_ID, STREAM_ID, "baseline-v1")));
    }

    private static BaselineStreamSummary summary() {
        return summary(0);
    }

    private static Document document(BaselineStreamSummary summary) {
        return document(MongoStreamSummaryProjectionAdapter.summaryDocument(summary));
    }

    private static Document document(Document summary) {
        return new Document("therapySessionId", SESSION_ID.toString())
                .append("streamId", STREAM_ID.toString())
                .append("calculationVersion", "baseline-v1")
                .append("admittedBatchCount", 3L)
                .append("summary", summary);
    }

    private static BaselineStreamSummary summary(int missingRangeCount) {
        long maximumSequence = 2L * missingRangeCount;
        var ranges = IntStream.range(0, missingRangeCount)
                .mapToObj(index -> new BaselineStreamSummary.SequenceRange(1L + 2L * index, 1L + 2L * index))
                .toList();
        return new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1, 1, SESSION_ID, STREAM_ID,
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
    }
}
