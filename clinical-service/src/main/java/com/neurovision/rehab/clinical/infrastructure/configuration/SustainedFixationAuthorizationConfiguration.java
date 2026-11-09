package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeSustainedFixationSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.Dependent;
import java.time.Clock;

@ApplicationScoped
public class SustainedFixationAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeSustainedFixationSessionQueryService sustainedFixationAuthorization(VisualSearchAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeSustainedFixationSessionQueryService(snapshots, Clock.systemUTC());
    }
}
