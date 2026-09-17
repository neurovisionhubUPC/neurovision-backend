package com.neurovision.rehab.clinical.application.internal.queryservices;

public final class TelemetryIngestionDeniedException extends RuntimeException {
    public TelemetryIngestionDeniedException() {
        super("Telemetry ingestion is not permitted");
    }
}
