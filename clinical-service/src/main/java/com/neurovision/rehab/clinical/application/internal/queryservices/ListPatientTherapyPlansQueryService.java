package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticConfigurationStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanStatus;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Optional;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import com.neurovision.rehab.clinical.application.internal.commandservices.StartTherapySessionCommandService;
import static com.neurovision.rehab.clinical.application.internal.queryservices.PatientDailyAvailability.*;

public final class ListPatientTherapyPlansQueryService {
    private final ClinicalPatientRepository patients;
    private final TherapyPlanRepository plans;
    private final TherapyPlanRevisionRepository revisions;
    private final DoctorPatientAssignmentRepository assignments;
    private final TherapeuticExerciseConfigurationRepository configurations;
    private final TherapySessionRepository sessions;
    private final Clock clock;

    public ListPatientTherapyPlansQueryService(ClinicalPatientRepository patients,
            TherapyPlanRepository plans, TherapyPlanRevisionRepository revisions,
            DoctorPatientAssignmentRepository assignments,
            TherapeuticExerciseConfigurationRepository configurations, TherapySessionRepository sessions, Clock clock) {
        this.patients = patients;
        this.plans = plans;
        this.revisions = revisions;
        this.assignments = assignments;
        this.configurations = configurations;
        this.sessions = sessions;
        this.clock = clock;
    }

    public List<PatientTherapyPlanView> handle(CognitoSubject patientSubject) {
        var patient = patients.findByPatientSubject(patientSubject)
                .orElseThrow(() -> new SecurityException("Patient is not registered"));
        var now = clock.instant();
        var currentPlans = plans.findByClinicalPatientId(patient.id()).stream()
                .filter(plan -> assignments.findActiveByClinicalPatientIdAndDoctorSubject(
                        patient.id(), plan.doctorSubject()).isPresent())
                .map(plan -> TherapyPlanRevision.applyEligible(plan, revisions.findEffective(plan.id(), now), now))
                .filter(plan -> plan.status() == TherapyPlanStatus.ACTIVE)
                .toList();
        if (currentPlans.isEmpty()) return List.of();
        var active = sessions.findActiveByPatient(patient.id());
        if (active.isPresent() && active.get().status() != TherapySessionStatus.ACTIVE)
            throw new IllegalStateException("Invalid active session observation");
        boolean expired = active.isPresent() && !now.isBefore(active.get().leaseExpiresAt().orElseThrow());
        return currentPlans.stream().map(plan -> {
            var effective = plan.dailyExercisePrescriptions().keySet().stream()
                    .map(TherapeuticExercise::from)
                    .map(exercise -> configurations.findEffective(plan.id(), exercise, now))
                    .flatMap(Optional::stream)
                    .filter(configuration -> configuration.therapyPlanId().equals(plan.id()))
                    .filter(configuration -> configuration.status() == TherapeuticConfigurationStatus.ACTIVE)
                    .filter(configuration -> !configuration.effectiveFrom().isAfter(now))
                    .toList();
            return new PatientTherapyPlanView(plan, effective, availability(plan, effective, active.isPresent(), expired, now));
        }).toList();
    }

    private PatientDailyAvailability availability(TherapyPlan plan, List<TherapeuticExerciseConfiguration> effective,
            boolean active, boolean expired, Instant now) {
        var date = now.atZone(StartTherapySessionCommandService.QUOTA_ZONE).toLocalDate();
        if (expired) {
            // Reconciliation belongs to POST start; do not even query stale quota/exposure totals here.
            return new PatientDailyAvailability(date, now, null, plan.dailyExercisePrescriptions().keySet().stream()
                    .map(code -> new ExerciseAvailability(code, null, Status.CHECK_AT_START, null)).toList());
        }
        long slots = nonNegative(sessions.countConsumedSlots(plan.id(), date));
        long exposure = nonNegative(sessions.sumExposureSeconds(plan.id(), date));
        var rows = new ArrayList<ExerciseAvailability>();
        for (var prescription : plan.dailyExercisePrescriptions().entrySet()) {
            var code = prescription.getKey();
            long consumed = nonNegative(sessions.countConsumedExerciseSlots(plan.id(), date, code));
            Reason reason = null;
            if (active) reason = Reason.ACTIVE_SESSION;
            else if (!usableConfiguration(plan, code, effective)) reason = Reason.CONFIGURATION_UNAVAILABLE;
            else reason = plan.admissionBlocker(code, slots, consumed, exposure)
                    .map(blocker -> Reason.valueOf(blocker.name())).orElse(null);
            rows.add(new ExerciseAvailability(code, Math.max(0L, prescription.getValue().longValue() - consumed),
                    reason == null ? Status.AVAILABLE : Status.BLOCKED, reason));
        }
        return new PatientDailyAvailability(date, now, Math.max(0L, plan.dailySessionLimit() - slots), rows);
    }

    private static boolean usableConfiguration(TherapyPlan plan, ExerciseCode code,
            List<TherapeuticExerciseConfiguration> effective) {
        return effective.stream().filter(c -> c.exercise() == TherapeuticExercise.from(code))
                .anyMatch(c -> !(c.parameters() instanceof TherapeuticExerciseParameters.Prescribed prescribed)
                        || prescribed.completionTarget().criterion() != CompletionTarget.Criterion.ACTIVE_TIME
                        || prescribed.completionTarget().target() <= plan.maximumSessionDurationMinutes() * 60L);
    }

    private static long nonNegative(long value) {
        if (value < 0) throw new IllegalStateException("Invalid daily accounting observation");
        return value;
    }
}
