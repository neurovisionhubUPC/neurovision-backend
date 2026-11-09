package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Keep the existing final query service independent of CDI and normal-scope proxies. */
@ApplicationScoped
public class SustainedFixationTrialSummaryConfiguration {
    @Produces @Dependent
    public SustainedFixationTrialSummaryService sustainedFixationTrialSummaryService(
            SustainedFixationTrialSummaryAuthorizationPort authorization,SustainedFixationTrialResultReader reader) {
        return new SustainedFixationTrialSummaryService(authorization,reader);
    }
}
