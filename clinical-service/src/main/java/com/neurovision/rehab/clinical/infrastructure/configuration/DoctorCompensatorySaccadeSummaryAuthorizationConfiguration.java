package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorCompensatorySaccadeSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorCompensatorySaccadeSummaryAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeDoctorCompensatorySaccadeSummaryQueryService doctorCompensatorySaccadeSummaryAuthorization(
            DoctorVisualSearchSummarySnapshotRepository snapshots) {
        return new AuthorizeDoctorCompensatorySaccadeSummaryQueryService(snapshots);
    }
}
