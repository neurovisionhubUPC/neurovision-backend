package com.neurovision.rehab.analytics.application.ports.out.authorization;

import java.util.UUID;

/** Delegates therapy-session read policy to the Clinical bounded context. */
public interface HistoricalHeatmapReadAuthorizationPort {
    Decision authorize(UUID therapySessionId, String originalAccessToken);

    enum Decision { PERMITTED, DENIED, INVALID_TOKEN, UNAVAILABLE }
}
