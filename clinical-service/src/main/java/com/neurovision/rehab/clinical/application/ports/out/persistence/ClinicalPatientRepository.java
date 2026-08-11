package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.Optional;

public interface ClinicalPatientRepository {

    Optional<ClinicalPatient> findByPatientSubject(CognitoSubject patientSubject);

    void save(ClinicalPatient clinicalPatient);
}
