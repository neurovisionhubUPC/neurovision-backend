package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorSystematicExplorationSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorSystematicExplorationSummaryAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeDoctorSystematicExplorationSummaryQueryService doctorSystematicExplorationSummaryAuthorization(
            DoctorVisualSearchSummarySnapshotRepository snapshots) {
        return new AuthorizeDoctorSystematicExplorationSummaryQueryService(snapshots);
    }
}
