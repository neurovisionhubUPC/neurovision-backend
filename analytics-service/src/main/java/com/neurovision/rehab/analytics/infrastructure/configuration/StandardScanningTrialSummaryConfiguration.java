package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.queryservices.StandardScanningTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Keep the existing final query service independent of CDI and normal-scope proxies. */
@ApplicationScoped
public class StandardScanningTrialSummaryConfiguration {
    @Produces @Dependent
    public StandardScanningTrialSummaryService standardScanningTrialSummaryService(
            StandardScanningTrialSummaryAuthorizationPort authorization,StandardScanningTrialResultReader reader) {
        return new StandardScanningTrialSummaryService(authorization,reader);
    }
}
