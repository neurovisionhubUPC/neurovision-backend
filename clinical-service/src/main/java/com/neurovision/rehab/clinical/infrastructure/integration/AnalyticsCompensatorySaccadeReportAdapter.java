package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort;
import java.util.*;

/** Dedicated fixed-origin Analytics summary adapter; original DOCTOR credential only. */
public final class AnalyticsCompensatorySaccadeReportAdapter implements SessionReportCompensatorySaccadePort {
    @FunctionalInterface
    interface Get { JsonNode get(UUID session, Origin origin, String token) throws Exception; }
    private final Get get;
    public AnalyticsCompensatorySaccadeReportAdapter(ReportAnalyticsHttpClient client) { this(Objects.requireNonNull(client)::getCompensatorySaccade); }
    AnalyticsCompensatorySaccadeReportAdapter(Get get) { this.get = Objects.requireNonNull(get); }
    public Summary observe(UUID session, Origin origin, String originalAccessToken) {
        try {
            Objects.requireNonNull(session); Objects.requireNonNull(origin); ReportAnalyticsHttpClient.requireToken(originalAccessToken);
            var summary = CompensatorySaccadeReportJson.summary(get.get(session,origin,originalAccessToken));
            if (!session.equals(summary.therapySessionId()) || origin != summary.dataOrigin()) throw new IllegalArgumentException();
            return summary;
        } catch (Exception unavailable) { throw new IllegalStateException("Report compensatory saccade unavailable"); }
    }
}
