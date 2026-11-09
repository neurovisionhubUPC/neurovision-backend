package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveCompensatorySaccadeTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Explicit composition without adding CDI dependencies to the existing application service. */
@ApplicationScoped
public class CompensatorySaccadeTrialReceptionConfiguration {
    @Produces @Dependent
    public ReceiveCompensatorySaccadeTrialResultService receiveCompensatorySaccadeTrialResultService(
            CompensatorySaccadeTrialAuthorizationPort authorization, CompensatorySaccadeTrialResultStore store) {
        return new ReceiveCompensatorySaccadeTrialResultService(authorization, store);
    }
}
