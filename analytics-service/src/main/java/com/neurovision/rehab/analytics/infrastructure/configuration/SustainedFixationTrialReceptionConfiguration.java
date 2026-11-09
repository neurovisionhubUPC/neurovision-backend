package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSustainedFixationTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Explicit composition without adding CDI dependencies to the existing application service. */
@ApplicationScoped
public class SustainedFixationTrialReceptionConfiguration {
    @Produces @Dependent
    public ReceiveSustainedFixationTrialResultService receiveSustainedFixationTrialResultService(
            SustainedFixationTrialAuthorizationPort authorization, SustainedFixationTrialResultStore store) {
        return new ReceiveSustainedFixationTrialResultService(authorization, store);
    }
}
