package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Explicit composition without adding CDI dependencies to the existing application service. */
@ApplicationScoped
public class SystematicExplorationTrialReceptionConfiguration {
    @Produces @Dependent
    public ReceiveSystematicExplorationTrialResultService receiveSystematicExplorationTrialResultService(
            SystematicExplorationTrialAuthorizationPort authorization, SystematicExplorationTrialResultStore store) {
        return new ReceiveSystematicExplorationTrialResultService(authorization, store);
    }
}
