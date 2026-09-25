package com.neurovision.rehab.analytics.interfaces.rest.resource;

import java.time.Instant;
import java.util.List;

/** Bounded, pseudonymous projection metadata and baseline-v1 metrics. */
public record AnalyticsSummaryResponse(
        int schemaVersion,
        int sourceSchemaVersion,
        String therapySessionId,
        String streamId,
        String calculationVersion,
        long revision,
        CompletenessResponse completeness,
        MetricsResponse metrics) {

    public record CompletenessResponse(
            long minimumSequence,
            long maximumSequence,
            long highSequence,
            long missingSequenceRangeCount,
            boolean missingSequenceRangesTruncated,
            List<SequenceRangeResponse> missingSequenceRanges,
            long lateArrivalCount,
            boolean unknownSessionBoundary,
            TimeRangeResponse observedTimeRange,
            SampleCountsResponse sampleCounts) {}

    public record MetricsResponse(
            NumericSummaryResponse confidence,
            List<Long> confidenceHistogram,
            HeatmapMetricsResponse heatmap,
            CoverageResponse coverage,
            GazePathResponse gazePath,
            GameplayMetricsResponse gameplay) {}

    public record SequenceRangeResponse(long fromInclusive, long toInclusive) {}
    public record TimeRangeResponse(Instant minimum, Instant maximum) {}
    public record SampleCountsResponse(long gaze, long gameplay, long total) {}
    public record NumericSummaryResponse(long count, Double minimum, Double maximum, Double mean) {}
    public record OrderedNumericSummaryResponse(
            NumericSummaryResponse distribution, Double first, Double last, Double signedDelta) {}
    public record HeatmapMetricsResponse(int rows, int columns, List<Long> cellCounts) {}
    public record CoverageResponse(BoundingBoxResponse boundingBox, long occupiedCellCount, double occupiedCellRatio) {}
    public record BoundingBoxResponse(
            double minimumX, double minimumY, double maximumX, double maximumY,
            double width, double height, double area) {}
    public record GazePathResponse(double totalDistance, NumericSummaryResponse velocity,
            long excludedNonPositiveDeltaCount) {}
    public record GameplayMetricsResponse(
            NumericSummaryResponse playerTargetDistance,
            OrderedNumericSummaryResponse score,
            OrderedNumericSummaryResponse speed,
            OrderedNumericSummaryResponse progress) {}
}
