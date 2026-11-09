package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeCompensatorySaccadeSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.Dependent;
import java.time.Clock;

@ApplicationScoped
public class CompensatorySaccadeAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeCompensatorySaccadeSessionQueryService compensatorySaccadeAuthorization(VisualSearchAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeCompensatorySaccadeSessionQueryService(snapshots, Clock.systemUTC());
    }
}
