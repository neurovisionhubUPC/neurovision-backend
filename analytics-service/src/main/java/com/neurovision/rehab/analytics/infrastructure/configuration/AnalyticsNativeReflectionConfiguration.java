package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers the Kafka wire records deserialized dynamically by Jackson in native images. */
@RegisterForReflection(targets = {
        TelemetryBatchAcceptedV1.class,
        TelemetryBatchAcceptedV1.GazeSampleV1.class,
        TelemetryBatchAcceptedV1.GameplaySampleV1.class
})
public final class AnalyticsNativeReflectionConfiguration {
    private AnalyticsNativeReflectionConfiguration() {}
}
