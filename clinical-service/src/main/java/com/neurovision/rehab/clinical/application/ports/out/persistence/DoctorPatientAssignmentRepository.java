package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.Optional;

public interface DoctorPatientAssignmentRepository {

    Optional<DoctorPatientAssignment> findActiveByClinicalPatientIdAndDoctorSubject(
            ClinicalPatientId clinicalPatientId, CognitoSubject doctorSubject);

    void save(DoctorPatientAssignment assignment);
}
