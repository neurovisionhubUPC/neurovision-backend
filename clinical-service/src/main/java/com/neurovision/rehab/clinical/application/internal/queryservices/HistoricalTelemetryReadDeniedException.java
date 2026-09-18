package com.neurovision.rehab.clinical.application.internal.queryservices;

public final class HistoricalTelemetryReadDeniedException extends RuntimeException {
    public HistoricalTelemetryReadDeniedException() {
        super("Historical telemetry read is not permitted");
    }
}
