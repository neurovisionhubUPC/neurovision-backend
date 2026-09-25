package com.neurovision.rehab.analytics.interfaces.rest.transform;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.BoundingBoxResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.CompletenessResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.CoverageResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.GameplayMetricsResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.GazePathResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.HeatmapMetricsResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.MetricsResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.NumericSummaryResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.OrderedNumericSummaryResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.SampleCountsResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.SequenceRangeResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.AnalyticsSummaryResponse.TimeRangeResponse;

public final class AnalyticsSummaryRestAssembler {
    public static final int MAX_MISSING_SEQUENCE_RANGES = 1_000;

    private AnalyticsSummaryRestAssembler() {}

    public static AnalyticsSummaryResponse toResponse(AnalyticsSummaryProjection projection) {
        var summary = projection.summary();
        var identity = projection.identity();
        var sourceRanges = summary.missingSequenceRanges();
        var missingRangePreview = sourceRanges.stream().limit(MAX_MISSING_SEQUENCE_RANGES)
                .map(range -> new SequenceRangeResponse(range.fromInclusive(), range.toInclusive()))
                .toList();
        return new AnalyticsSummaryResponse(1, summary.sourceSchemaVersion(),
                identity.therapySessionId().toString(), identity.streamId().toString(),
                identity.calculationVersion(), projection.revision(),
                new CompletenessResponse(summary.minimumSequence(), summary.maximumSequence(), summary.highSequence(),
                        sourceRanges.size(), sourceRanges.size() > MAX_MISSING_SEQUENCE_RANGES, missingRangePreview,
                        summary.lateArrivalCount(), summary.unknownSessionBoundary(),
                        summary.observedTimeRange().map(range ->
                                new TimeRangeResponse(range.minimum(), range.maximum())).orElse(null),
                        new SampleCountsResponse(summary.sampleCounts().gaze(), summary.sampleCounts().gameplay(),
                                summary.sampleCounts().total())),
                new MetricsResponse(numeric(summary.confidence()), summary.confidenceHistogram(),
                        new HeatmapMetricsResponse(summary.heatmap().rows(), summary.heatmap().columns(),
                                summary.heatmap().cellCounts()),
                        coverage(summary.coverage()),
                        new GazePathResponse(summary.gazePath().totalDistance(), numeric(summary.gazePath().velocity()),
                                summary.gazePath().excludedNonPositiveDeltaCount()),
                        new GameplayMetricsResponse(numeric(summary.gameplay().playerTargetDistance()),
                                ordered(summary.gameplay().score()), ordered(summary.gameplay().speed()),
                                ordered(summary.gameplay().progress()))));
    }

    private static CoverageResponse coverage(BaselineStreamSummary.Coverage coverage) {
        var box = coverage.boundingBox().map(value -> new BoundingBoxResponse(value.minimumX(), value.minimumY(),
                value.maximumX(), value.maximumY(), value.width(), value.height(), value.area())).orElse(null);
        return new CoverageResponse(box, coverage.occupiedCellCount(), coverage.occupiedCellRatio());
    }

    private static NumericSummaryResponse numeric(BaselineStreamSummary.NumericSummary summary) {
        return new NumericSummaryResponse(summary.count(), optional(summary.minimum()),
                optional(summary.maximum()), optional(summary.mean()));
    }

    private static OrderedNumericSummaryResponse ordered(BaselineStreamSummary.OrderedNumericSummary summary) {
        return new OrderedNumericSummaryResponse(numeric(summary.distribution()), optional(summary.first()),
                optional(summary.last()), optional(summary.signedDelta()));
    }

    private static Double optional(java.util.OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}
