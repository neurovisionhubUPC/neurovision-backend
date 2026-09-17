package com.neurovision.rehab.telemetry.application.ports.out.persistence;

/** A non-retryable batch conflict or invalid persisted sample. No payload is retained. */
public final class TelemetryPersistenceRejectedException extends RuntimeException {
    public TelemetryPersistenceRejectedException() {
        super("Telemetry persistence rejected");
    }
}
