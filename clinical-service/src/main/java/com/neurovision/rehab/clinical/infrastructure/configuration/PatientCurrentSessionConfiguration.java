package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.queryservices.GetPatientCurrentSessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class PatientCurrentSessionConfiguration {
    @Produces @Dependent
    public GetPatientCurrentSessionQueryService patientCurrentSession(ClinicalPatientRepository patients,
            TherapySessionRepository sessions, DoctorPatientAssignmentRepository assignments) {
        return new GetPatientCurrentSessionQueryService(patients, sessions, assignments, Clock.systemUTC());
    }
}
