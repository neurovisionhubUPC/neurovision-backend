package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeSystematicExplorationSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.Dependent;
import java.time.Clock;

@ApplicationScoped
public class SystematicExplorationAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeSystematicExplorationSessionQueryService systematicExplorationAuthorization(VisualSearchAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeSystematicExplorationSessionQueryService(snapshots, Clock.systemUTC());
    }
}
