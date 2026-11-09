package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientTherapyPlanView;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.interfaces.rest.resource.PatientTherapyPlanResponse;
import com.neurovision.rehab.clinical.interfaces.rest.resource.PatientTherapySessionResponse;
import java.util.Map;
import java.util.TreeMap;

public final class PatientTherapyRestAssembler {
    private PatientTherapyRestAssembler() {}

    public static PatientTherapyPlanResponse toResponse(PatientTherapyPlanView view, ObjectMapper mapper) {
        var plan = view.plan();
        var prescriptions = new TreeMap<String, Integer>();
        plan.dailyExercisePrescriptions().forEach((code, count) -> prescriptions.put(code.value(), count));
        var configurations = view.effectiveConfigurations().stream()
                .map(configuration -> new PatientTherapyPlanResponse.EffectiveConfiguration(
                        configuration.id().value(), configuration.exercise().code().value(),
                        configuration.version(), configuration.parameterSchemaVersion(),
                        configuration.effectiveFrom(), mapper.valueToTree(configuration.parameters())))
                .toList();
        return new PatientTherapyPlanResponse(plan.id().value(), plan.status().name(),
                plan.dailySessionLimit(), plan.dailyTargetDurationMinutes(),
                plan.dailyHardSafetyLimitMinutes().isPresent()
                        ? plan.dailyHardSafetyLimitMinutes().getAsInt() : null,
                plan.minimumValidDurationMinutes(), plan.maximumSessionDurationMinutes(),
                plan.minimumValidSampleCount(), Map.copyOf(prescriptions), configurations,
                new PatientTherapyPlanResponse.DailyAvailability(view.dailyAvailability().quotaDate(),
                        view.dailyAvailability().evaluatedAt(), view.dailyAvailability().remainingPlanSessionSlots(),
                        view.dailyAvailability().exercises().stream().map(exercise -> new PatientTherapyPlanResponse.ExerciseAvailability(
                                exercise.exerciseCode().value(), exercise.remainingPrescribedSlots(), exercise.status().name(),
                                exercise.reason() == null ? null : exercise.reason().name())).toList()));
    }

    public static PatientTherapySessionResponse toResponse(TherapySession session, ObjectMapper mapper) {
        return new PatientTherapySessionResponse(session.id().value(), session.therapyPlanId().value(),
                session.exerciseCode().value(), session.status().name(), session.quotaDate(),
                session.therapeuticConfigurationId().value(), session.configurationVersion(),
                session.parameterSchemaVersion(), mapper.valueToTree(session.configurationParameters()),
                session.minimumValidDurationMinutes(), session.maximumSessionDurationMinutes(),
                session.minimumValidSampleCount(), session.startedAt(),
                session.leaseExpiresAt().orElse(null));
    }
}
