package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Keep the existing final query service independent of CDI and normal-scope proxies. */
@ApplicationScoped
public class VisualSearchTrialSummaryConfiguration {
    @Produces @Dependent
    public VisualSearchTrialSummaryService visualSearchTrialSummaryService(
            VisualSearchTrialSummaryAuthorizationPort authorization,VisualSearchTrialResultReader reader) {
        return new VisualSearchTrialSummaryService(authorization,reader);
    }
}
