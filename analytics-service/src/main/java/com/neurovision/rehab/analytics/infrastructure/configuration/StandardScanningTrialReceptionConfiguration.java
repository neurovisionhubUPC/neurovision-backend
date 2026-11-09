package com.neurovision.rehab.analytics.infrastructure.configuration;

import com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveStandardScanningTrialResultService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/** Explicit composition without adding CDI dependencies to the existing application service. */
@ApplicationScoped
public class StandardScanningTrialReceptionConfiguration {
    @Produces @Dependent
    public ReceiveStandardScanningTrialResultService receiveStandardScanningTrialResultService(
            StandardScanningTrialAuthorizationPort authorization, StandardScanningTrialResultStore store) {
        return new ReceiveStandardScanningTrialResultService(authorization, store);
    }
}
