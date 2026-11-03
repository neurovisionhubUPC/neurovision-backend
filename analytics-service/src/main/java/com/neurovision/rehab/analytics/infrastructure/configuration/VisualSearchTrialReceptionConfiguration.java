package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveVisualSearchTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Explicit composition without adding CDI dependencies to the existing application service. */
@ApplicationScoped
public class VisualSearchTrialReceptionConfiguration {
    @Produces @Dependent
    public ReceiveVisualSearchTrialResultService receiveVisualSearchTrialResultService(
            VisualSearchTrialAuthorizationPort authorization, VisualSearchTrialResultStore store) {
        return new ReceiveVisualSearchTrialResultService(authorization, store);
    }
}
