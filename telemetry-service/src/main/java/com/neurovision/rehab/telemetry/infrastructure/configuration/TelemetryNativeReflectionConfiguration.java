package com.neurovision.rehab.telemetry.infrastructure.configuration;

import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers REST records deserialized through ObjectMapper rather than a typed resource parameter. */
@RegisterForReflection(targets = {
        TelemetryBatchV1.class,
        TelemetryBatchV1.GazeSampleV1.class,
        TelemetryBatchV1.GameplaySampleV1.class
})
public final class TelemetryNativeReflectionConfiguration {
    private TelemetryNativeReflectionConfiguration() {}
}
