package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class CreateTherapyPlanCommandService {
    private final DoctorPatientAssignmentRepository assignments;
    private final TherapyPlanRepository plans;
    private final Supplier<UUID> identifiers;

    public CreateTherapyPlanCommandService(
            DoctorPatientAssignmentRepository assignments, TherapyPlanRepository plans,
            Supplier<UUID> identifiers) {
        this.assignments = Objects.requireNonNull(assignments);
        this.plans = Objects.requireNonNull(plans);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public TherapyPlan handle(CreateTherapyPlanCommand command) {
        Objects.requireNonNull(command);
        assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                        command.clinicalPatientId(), command.doctorSubject())
                .orElseThrow(() -> new SecurityException("Active doctor-patient assignment is required"));
        if (command.dailyHardSafetyLimitMinutes() != null
                && command.dailyHardSafetyLimitMinutes() < command.maximumSessionDurationMinutes()) {
            throw new IllegalArgumentException("Initial hard safety limit must accommodate a maximum-duration session");
        }
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(identifiers.get()),
                command.clinicalPatientId(), command.doctorSubject(), TherapyPlanStatus.ACTIVE, 1,
                command.dailySessionLimit(), command.dailyTargetDurationMinutes(),
                command.dailyHardSafetyLimitMinutes(), command.minimumValidDurationMinutes(),
                command.maximumSessionDurationMinutes(), command.minimumValidSampleCount(),
                command.dailyExercisePrescriptions());
        plans.save(plan);
        return plan;
    }
}
