package com.neurovision.rehab.clinical.infrastructure.integration;

import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort;
import java.util.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.ReportAnalyticsHttpClient.Resource.*;
import static com.neurovision.rehab.clinical.infrastructure.integration.ReportAnalyticsHttpClient.unavailable;

/** One sequential first discovery window and three reads per stream: at most 61 requests, no retries.
 * Time limits apply per request, not to the entire observation. Registered by report-preview wiring.
 */
public final class AnalyticsSessionReportAdapter implements SessionReportAnalyticsPort {
    private final ReportAnalyticsHttpClient.Get get;
    public AnalyticsSessionReportAdapter(ReportAnalyticsHttpClient client) { this(Objects.requireNonNull(client)::get); }
    public AnalyticsSessionReportAdapter(ReportAnalyticsHttpClient.Get get) { this.get = Objects.requireNonNull(get); }

    @Override public Observation observe(UUID therapySessionId, String originalAccessToken) {
        try {
            Objects.requireNonNull(therapySessionId);
            ReportAnalyticsHttpClient.requireToken(originalAccessToken);
            var discovery = ReportAnalyticsDecoder.discovery(therapySessionId,
                    get.get(therapySessionId, null, DISCOVERY, originalAccessToken));
            if (discovery.items().isEmpty()) return new ConfirmedNoEvidence(therapySessionId);
            var streams = new ArrayList<Stream>();
            for (var item : discovery.items()) {
                var summary = ReportAnalyticsDecoder.summary(therapySessionId, item,
                        get.get(therapySessionId, item.streamId(), SUMMARY, originalAccessToken));
                var heatmap = get.get(therapySessionId, item.streamId(), HEATMAP, originalAccessToken);
                var gaze = get.get(therapySessionId, item.streamId(), GAZE, originalAccessToken);
                streams.add(ReportAnalyticsDecoder.stream(summary, heatmap, gaze));
            }
            return new ObservedEvidence(therapySessionId, 0, 20, discovery.hasNext(), streams);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception unavailable) { throw unavailable(); }
    }
}
