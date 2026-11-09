package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class PatientTherapySessionDetailConfiguration {
    @Produces @Dependent
    public GetPatientTherapySessionQueryService patientTherapySessionDetail(ClinicalPatientRepository patients,
            TherapySessionRepository sessions, DoctorTherapySessionReadRepository details) {
        return new GetPatientTherapySessionQueryService(patients, sessions, details);
    }
}
