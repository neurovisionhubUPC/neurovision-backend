package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort;
import java.util.*;

/** Dedicated fixed-origin Analytics summary adapter; original DOCTOR credential only. */
public final class AnalyticsStandardScanningReportAdapter implements SessionReportStandardScanningPort {
    @FunctionalInterface
    interface Get { JsonNode get(UUID session, Origin origin, String token) throws Exception; }
    private final Get get;
    public AnalyticsStandardScanningReportAdapter(ReportAnalyticsHttpClient client) { this(Objects.requireNonNull(client)::getStandardScanning); }
    AnalyticsStandardScanningReportAdapter(Get get) { this.get = Objects.requireNonNull(get); }
    public Summary observe(UUID session, Origin origin, String originalAccessToken) {
        try {
            Objects.requireNonNull(session); Objects.requireNonNull(origin); ReportAnalyticsHttpClient.requireToken(originalAccessToken);
            var summary = StandardScanningReportJson.summary(get.get(session,origin,originalAccessToken));
            if (!session.equals(summary.therapySessionId()) || origin != summary.dataOrigin()) throw new IllegalArgumentException();
            return summary;
        } catch (Exception unavailable) { throw new IllegalStateException("Report standard scanning unavailable"); }
    }
}
