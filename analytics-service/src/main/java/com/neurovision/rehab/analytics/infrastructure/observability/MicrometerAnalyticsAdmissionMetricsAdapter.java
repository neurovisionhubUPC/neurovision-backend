package com.neurovision.rehab.analytics.infrastructure.observability;

import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsAdmissionMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MicrometerAnalyticsAdmissionMetricsAdapter implements AnalyticsAdmissionMetricsPort {
    private final MeterRegistry meters;

    @Inject
    public MicrometerAnalyticsAdmissionMetricsAdapter(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void record(Outcome outcome) {
        String label = switch (outcome) {
            case ADMITTED -> "admitted";
            case DUPLICATE -> "duplicate";
            case BATCH_CONFLICT -> "batch_conflict";
            case SEQUENCE_CONFLICT -> "sequence_conflict";
        };
        meters.counter("analytics.admissions", "outcome", label).increment();
    }
}
