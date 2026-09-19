package com.neurovision.rehab.analytics.interfaces.rest.resource;

import java.time.Instant;
import java.util.List;

public record HeatmapProjectionResponse(
        int contractVersion,
        int sourceSchemaVersion,
        String therapySessionId,
        String streamId,
        String calculationVersion,
        long revision,
        int rows,
        int columns,
        List<Long> cellCounts,
        String coordinateSpace,
        String cellWeight,
        boolean confidenceFiltered,
        long minimumSequence,
        long maximumSequence,
        long highSequence,
        List<SequenceRangeResponse> missingSequenceRanges,
        long lateArrivalCount,
        boolean unknownSessionBoundary,
        long gazeSampleCount,
        TimeRangeResponse observedTimeRange) {

    public record SequenceRangeResponse(long fromInclusive, long toInclusive) {}

    public record TimeRangeResponse(Instant minimum, Instant maximum) {}
}
