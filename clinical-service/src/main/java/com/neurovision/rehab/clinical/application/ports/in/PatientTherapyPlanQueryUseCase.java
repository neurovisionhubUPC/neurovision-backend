package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.application.internal.queryservices.PatientTherapyPlanView;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.List;

public interface PatientTherapyPlanQueryUseCase {
    List<PatientTherapyPlanView> handle(CognitoSubject patientSubject);
}
