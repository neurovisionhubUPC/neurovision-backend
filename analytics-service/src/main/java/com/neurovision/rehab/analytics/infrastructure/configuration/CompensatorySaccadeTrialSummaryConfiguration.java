package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.queryservices.CompensatorySaccadeTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Keep the existing final query service independent of CDI and normal-scope proxies. */
@ApplicationScoped
public class CompensatorySaccadeTrialSummaryConfiguration {
    @Produces @Dependent
    public CompensatorySaccadeTrialSummaryService compensatorySaccadeTrialSummaryService(
            CompensatorySaccadeTrialSummaryAuthorizationPort authorization,CompensatorySaccadeTrialResultReader reader) {
        return new CompensatorySaccadeTrialSummaryService(authorization,reader);
    }
}
