package com.neurovision.rehab.analytics.interfaces.rest.resource;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public record AnalyticsSessionStreamsResponse(String therapySessionId, int page, int size, boolean hasNext, List<Item> items) {
    @RegisterForReflection
    public record Item(String streamId, String calculationVersion, long revision, SampleCounts sampleCounts) {}
    @RegisterForReflection
    public record SampleCounts(long gaze, long gameplay, long total) {}
}
