package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorStandardScanningSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorStandardScanningSummaryAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeDoctorStandardScanningSummaryQueryService doctorStandardScanningSummaryAuthorization(
            DoctorVisualSearchSummarySnapshotRepository snapshots) {
        return new AuthorizeDoctorStandardScanningSummaryQueryService(snapshots);
    }
}
