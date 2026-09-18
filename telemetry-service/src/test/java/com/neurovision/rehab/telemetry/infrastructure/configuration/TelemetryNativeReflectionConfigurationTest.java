package com.neurovision.rehab.telemetry.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TelemetryNativeReflectionConfigurationTest {
    @Test
    void registersEveryRecordUsedByManualBatchDeserialization() {
        var registration = TelemetryNativeReflectionConfiguration.class.getAnnotation(RegisterForReflection.class);

        assertNotNull(registration);
        assertEquals(Set.of(TelemetryBatchV1.class,
                        TelemetryBatchV1.GazeSampleV1.class,
                        TelemetryBatchV1.GameplaySampleV1.class),
                Set.of(registration.targets()));
    }
}
