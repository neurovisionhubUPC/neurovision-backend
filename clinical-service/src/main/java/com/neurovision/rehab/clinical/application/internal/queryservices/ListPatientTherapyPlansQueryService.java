package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticConfigurationStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanStatus;
import java.time.Clock;
import java.util.List;

public final class ListPatientTherapyPlansQueryService {
    private final ClinicalPatientRepository patients;
    private final TherapyPlanRepository plans;
    private final TherapyPlanRevisionRepository revisions;
    private final DoctorPatientAssignmentRepository assignments;
    private final TherapeuticExerciseConfigurationRepository configurations;
    private final Clock clock;

    public ListPatientTherapyPlansQueryService(ClinicalPatientRepository patients,
            TherapyPlanRepository plans, TherapyPlanRevisionRepository revisions,
            DoctorPatientAssignmentRepository assignments,
            TherapeuticExerciseConfigurationRepository configurations, Clock clock) {
        this.patients = patients;
        this.plans = plans;
        this.revisions = revisions;
        this.assignments = assignments;
        this.configurations = configurations;
        this.clock = clock;
    }

    public List<PatientTherapyPlanView> handle(CognitoSubject patientSubject) {
        var patient = patients.findByPatientSubject(patientSubject)
                .orElseThrow(() -> new SecurityException("Patient is not registered"));
        var now = clock.instant();
        return plans.findByClinicalPatientId(patient.id()).stream()
                .filter(plan -> assignments.findActiveByClinicalPatientIdAndDoctorSubject(
                        patient.id(), plan.doctorSubject()).isPresent())
                .map(plan -> TherapyPlanRevision.applyEligible(plan, revisions.findEffective(plan.id(), now), now))
                .filter(plan -> plan.status() == TherapyPlanStatus.ACTIVE)
                .map(plan -> new PatientTherapyPlanView(plan,
                        plan.dailyExercisePrescriptions().keySet().stream()
                                .map(TherapeuticExercise::from)
                                .map(exercise -> configurations.findEffective(plan.id(), exercise, now))
                                .flatMap(java.util.Optional::stream)
                                .filter(configuration -> configuration.therapyPlanId().equals(plan.id()))
                                .filter(configuration -> configuration.status() == TherapeuticConfigurationStatus.ACTIVE)
                                .filter(configuration -> !configuration.effectiveFrom().isAfter(now))
                                .toList()))
                .toList();
    }
}
