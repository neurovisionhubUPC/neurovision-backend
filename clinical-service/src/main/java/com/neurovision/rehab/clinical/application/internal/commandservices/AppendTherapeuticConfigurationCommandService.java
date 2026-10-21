package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorSchedulingEligibilityPort;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class AppendTherapeuticConfigurationCommandService {
    private final TherapyPlanRepository plans;
    private final DoctorPatientAssignmentRepository assignments;
    private final TherapeuticExerciseConfigurationRepository configurations;
    private final DoctorSchedulingEligibilityPort eligibility;
    private final Supplier<UUID> identifiers;
    private final Clock clock;

    public AppendTherapeuticConfigurationCommandService(
            TherapyPlanRepository plans,
            DoctorPatientAssignmentRepository assignments,
            TherapeuticExerciseConfigurationRepository configurations,
            Supplier<UUID> identifiers,
            Clock clock, DoctorSchedulingEligibilityPort eligibility) {
        this.plans = Objects.requireNonNull(plans);
        this.assignments = Objects.requireNonNull(assignments);
        this.configurations = Objects.requireNonNull(configurations);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Appends a server-validated configuration. The caller boundary must derive the doctor subject
     * from the authenticated doctor identity; a patient identity cannot satisfy the active assignment.
     */
    public TherapeuticExerciseConfiguration handle(AppendTherapeuticConfigurationCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        eligibility.requireEligible(command.doctorSubject());
        // Lock order is stable: plan first, then assignment. The plan lock also serializes
        // max(version)+1 allocation for every exercise in this plan.
        var plan = plans.findByIdForUpdate(command.therapyPlanId())
                .orElseThrow(() -> new NoSuchElementException("Therapy plan not found"));
        if (!plan.clinicalPatientId().equals(command.clinicalPatientId())
                || !plan.doctorSubject().equals(command.doctorSubject())) {
            throw new SecurityException("Doctor is not authorized for this therapy plan");
        }
        if (plan.status() != TherapyPlanStatus.ACTIVE) {
            throw new IllegalStateException("Therapy plan is not active");
        }
        if (!plan.dailyExercisePrescriptions().containsKey(command.exercise().code())) {
            throw new IllegalStateException("Exercise is not prescribed by the therapy plan");
        }
        assignments.findActiveByClinicalPatientIdAndDoctorSubjectForUpdate(
                        command.clinicalPatientId(), command.doctorSubject())
                .orElseThrow(() -> new SecurityException("Active doctor-patient assignment is required"));

        long nextVersion = configurations.findLatestVersion(command.therapyPlanId(), command.exercise())
                .orElse(0L) + 1L;
        var configuration = TherapeuticExerciseConfiguration.create(
                new TherapeuticConfigurationId(identifiers.get()), command.therapyPlanId(),
                command.exercise(), nextVersion, command.parameterSchemaVersion(), command.effectiveFrom(),
                command.doctorSubject(), clock.instant(), command.parameters());
        configurations.append(configuration);
        return configuration;
    }
}
