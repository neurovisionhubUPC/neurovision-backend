package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionResult;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode;
import com.neurovision.rehab.clinical.domain.model.valueobjects.IdempotencyKey;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;

public interface PatientTherapySessionStartUseCase {
    StartTherapySessionResult handle(CognitoSubject patientSubject, TherapyPlanId planId,
            ExerciseCode exerciseCode, IdempotencyKey idempotencyKey);
}
