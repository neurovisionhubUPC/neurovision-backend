package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Keep the existing final query service independent of CDI and normal-scope proxies. */
@ApplicationScoped
public class SystematicExplorationTrialSummaryConfiguration {
    @Produces @Dependent
    public SystematicExplorationTrialSummaryService systematicExplorationTrialSummaryService(
            SystematicExplorationTrialSummaryAuthorizationPort authorization,SystematicExplorationTrialResultReader reader) {
        return new SystematicExplorationTrialSummaryService(authorization,reader);
    }
}
