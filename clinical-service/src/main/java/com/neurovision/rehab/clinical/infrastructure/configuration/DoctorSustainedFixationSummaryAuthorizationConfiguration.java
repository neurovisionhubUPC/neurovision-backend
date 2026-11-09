package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorSustainedFixationSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorSustainedFixationSummaryAuthorizationConfiguration {
    @Produces @Dependent
    public AuthorizeDoctorSustainedFixationSummaryQueryService doctorSustainedFixationSummaryAuthorization(
            DoctorVisualSearchSummarySnapshotRepository snapshots) {
        return new AuthorizeDoctorSustainedFixationSummaryQueryService(snapshots);
    }
}
