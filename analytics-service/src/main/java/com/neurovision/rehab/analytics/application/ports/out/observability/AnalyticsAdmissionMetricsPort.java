package com.neurovision.rehab.analytics.application.ports.out.observability;

/** Best-effort observation of completed admission attempts, without event metadata. */
public interface AnalyticsAdmissionMetricsPort {
    void record(Outcome outcome);

    enum Outcome { ADMITTED, DUPLICATE, BATCH_CONFLICT, SEQUENCE_CONFLICT }
}
