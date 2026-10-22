package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DoctorTherapySessionReadServicesConfiguration {
    @Produces @ApplicationScoped
    DoctorTherapySessionQueryService sessions(DoctorTherapySessionReadRepository reads) {
        return new DoctorTherapySessionQueryService(reads);
    }
}
