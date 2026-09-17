package com.neurovision.rehab.telemetry.application.ports.out.authorization;

import java.util.UUID;

/** The credential is transient and must never be included in a telemetry publication. */
public interface TherapySessionAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalAccessToken);

    enum Decision { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }
}
