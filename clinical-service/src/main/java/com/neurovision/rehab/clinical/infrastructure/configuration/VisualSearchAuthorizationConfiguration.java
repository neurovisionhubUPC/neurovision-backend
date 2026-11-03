package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeVisualSearchSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class VisualSearchAuthorizationConfiguration {
    @Produces @ApplicationScoped
    public AuthorizeVisualSearchSessionQueryService visualSearchAuthorization(VisualSearchAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeVisualSearchSessionQueryService(snapshots, Clock.systemUTC());
    }
}
