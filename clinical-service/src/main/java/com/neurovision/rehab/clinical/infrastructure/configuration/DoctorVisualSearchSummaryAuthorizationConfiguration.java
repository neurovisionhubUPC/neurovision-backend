package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorVisualSearchSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorVisualSearchSummaryAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeDoctorVisualSearchSummaryQueryService doctorVisualSearchSummaryAuthorization(
            DoctorVisualSearchSummarySnapshotRepository snapshots) {
        return new AuthorizeDoctorVisualSearchSummaryQueryService(snapshots);
    }
}
