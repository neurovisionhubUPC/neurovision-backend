package com.neurovision.rehab.telemetry.infrastructure.configuration;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers records used by manual REST/Kafka Jackson codecs in the native image. */
@RegisterForReflection(targets = {
        TelemetryBatchV1.class,
        TelemetryBatchV1.GazeSampleV1.class,
        TelemetryBatchV1.GameplaySampleV1.class,
        TelemetryBatchAcceptedV1.class,
        TelemetryBatchAcceptedV1.GazeSampleV1.class,
        TelemetryBatchAcceptedV1.GameplaySampleV1.class,
        TelemetryBatch.GazeSample.class,
        TelemetryBatch.GameplaySample.class
})
public final class TelemetryNativeReflectionConfiguration {
    private TelemetryNativeReflectionConfiguration() {}
}
