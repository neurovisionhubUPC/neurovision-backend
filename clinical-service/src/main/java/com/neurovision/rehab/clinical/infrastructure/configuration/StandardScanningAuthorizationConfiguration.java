package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeStandardScanningSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.Dependent;
import java.time.Clock;

@ApplicationScoped
public class StandardScanningAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeStandardScanningSessionQueryService standardScanningAuthorization(VisualSearchAuthorizationSnapshotRepository snapshots) {
        return new AuthorizeStandardScanningSessionQueryService(snapshots, Clock.systemUTC());
    }
}
