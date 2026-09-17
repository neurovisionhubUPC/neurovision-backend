package com.neurovision.rehab.telemetry.application.internal.eventhandlers;

import com.neurovision.rehab.telemetry.application.ports.out.persistence.TelemetryBatchPersistencePort;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/** Coordinates an already validated event without coupling application code to Kafka. */
@ApplicationScoped
public class PersistTelemetryBatchEventHandler {
    private final TelemetryBatchPersistencePort persistence;

    @Inject
    public PersistTelemetryBatchEventHandler(TelemetryBatchPersistencePort persistence) {
        this.persistence = persistence;
    }

    public Uni<TelemetryBatchPersistencePort.Result> persist(
            TelemetryBatch batch, Instant serverReceivedAt, String payloadSha256) {
        return persistence.persist(batch, serverReceivedAt, payloadSha256);
    }
}
