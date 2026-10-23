package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionActivityRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = {
        CompletionTarget.class, TherapeuticExerciseParameters.Prescribed.class,
        SessionActivityProgress.class, SessionActivityEvent.class,
        SessionActivityRepository.Receipt.class, SessionActivityRepository.Completion.class,
        PatientSessionProgressUseCase.View.class
})
public final class SessionProgressReflectionConfiguration { }
