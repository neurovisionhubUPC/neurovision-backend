package com.neurovision.rehab.telemetry.application.ports.out.messaging;

import com.neurovision.rehab.telemetry.application.internal.validation.TelemetryValidationResult;
import java.util.UUID;

/** True only after the broker acknowledges publication; Phase 4 supplies that adapter. */
public interface TelemetryPublicationPort {
    boolean publish(Publication acceptedBatch);

    /** Server-owned metadata; deliberately contains no credential or direct patient identity. */
    record Publication(TelemetryValidationResult validation, UUID correlationId, String traceId) {}
}
