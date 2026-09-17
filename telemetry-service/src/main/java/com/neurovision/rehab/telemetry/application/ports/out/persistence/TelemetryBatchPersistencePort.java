package com.neurovision.rehab.telemetry.application.ports.out.persistence;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import io.smallrye.mutiny.Uni;
import java.time.Instant;

/** A single transaction must claim the batch and persist all samples. */
public interface TelemetryBatchPersistencePort {
    Uni<Result> persist(TelemetryBatch batch, Instant serverReceivedAt, String payloadSha256);

    enum Result { INSERTED, DUPLICATE }
}
