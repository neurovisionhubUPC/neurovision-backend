package com.neurovision.rehab.telemetry.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.neurovision.rehab.telemetry.interfaces.rest.resource.TelemetryBatchV1;
import com.neurovision.rehab.telemetry.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TelemetryNativeReflectionConfigurationTest {
    @Test
    void registersEveryPublicRecordUsedByManualBatchCodecs() {
        var registration = TelemetryNativeReflectionConfiguration.class.getAnnotation(RegisterForReflection.class);

        assertNotNull(registration);
        assertEquals(Set.of(TelemetryBatchV1.class,
                        TelemetryBatchV1.GazeSampleV1.class,
                        TelemetryBatchV1.GameplaySampleV1.class,
                        TelemetryBatchAcceptedV1.class,
                        TelemetryBatchAcceptedV1.GazeSampleV1.class,
                        TelemetryBatchAcceptedV1.GameplaySampleV1.class,
                        TelemetryBatch.GazeSample.class,
                        TelemetryBatch.GameplaySample.class),
                Set.of(registration.targets()));
    }

    @Test
    void registersPrivateStablePayloadForNativeHashSerialization() throws Exception {
        var type = Class.forName("com.neurovision.rehab.telemetry.interfaces.events.transform."
                + "TelemetryBatchPersistenceEventAssembler$StablePayload");
        assertNotNull(type.getAnnotation(RegisterForReflection.class));
    }
}
