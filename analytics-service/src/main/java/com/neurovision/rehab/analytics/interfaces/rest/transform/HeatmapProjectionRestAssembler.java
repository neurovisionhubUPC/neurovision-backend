package com.neurovision.rehab.analytics.interfaces.rest.transform;

import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import com.neurovision.rehab.analytics.interfaces.rest.resource.HeatmapProjectionResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.HeatmapProjectionResponse.SequenceRangeResponse;
import com.neurovision.rehab.analytics.interfaces.rest.resource.HeatmapProjectionResponse.TimeRangeResponse;

public final class HeatmapProjectionRestAssembler {
    private HeatmapProjectionRestAssembler() {}

    public static HeatmapProjectionResponse toResponse(HeatmapProjection projection) {
        var identity = projection.identity();
        return new HeatmapProjectionResponse(
                1,
                projection.sourceSchemaVersion(),
                identity.therapySessionId().toString(),
                identity.streamId().toString(),
                identity.calculationVersion(),
                projection.revision(),
                projection.rows(),
                projection.columns(),
                projection.cellCounts(),
                "normalized-0..1",
                "raw-gaze-sample-count",
                projection.confidenceFiltered(),
                projection.minimumSequence(),
                projection.maximumSequence(),
                projection.highSequence(),
                projection.missingSequenceRanges().stream()
                        .map(range -> new SequenceRangeResponse(
                                range.fromInclusive(), range.toInclusive()))
                        .toList(),
                projection.lateArrivalCount(),
                projection.unknownSessionBoundary(),
                projection.gazeSampleCount(),
                projection.observedTimeRange()
                        .map(range -> new TimeRangeResponse(range.minimum(), range.maximum()))
                        .orElse(null));
    }
}
