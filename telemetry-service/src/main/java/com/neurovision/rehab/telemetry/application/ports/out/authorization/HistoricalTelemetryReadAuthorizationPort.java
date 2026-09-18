package com.neurovision.rehab.telemetry.application.ports.out.authorization;

import java.util.UUID;

/** Separate from the ACTIVE/patient-only ingestion permission. */
public interface HistoricalTelemetryReadAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalAccessToken);

    enum Decision { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }
}
