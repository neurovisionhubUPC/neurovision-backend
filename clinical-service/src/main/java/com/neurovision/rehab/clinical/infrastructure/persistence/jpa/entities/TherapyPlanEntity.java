package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import jakarta.persistence.*;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Entity
@Table(name = "therapy_plans")
public class TherapyPlanEntity {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "clinical_patient_id", nullable = false, updatable = false) private UUID clinicalPatientId;
    @Column(name = "doctor_cognito_sub", nullable = false, length = 128, updatable = false) private String doctorSubject;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "configuration_version", nullable = false) private long configurationVersion;
    @Column(name = "daily_session_limit", nullable = false) private int dailySessionLimit;
    @Column(name = "daily_target_duration_minutes", nullable = false) private int dailyTargetDurationMinutes;
    @Column(name = "daily_hard_safety_limit_minutes") private Integer dailyHardSafetyLimitMinutes;
    @Column(name = "minimum_valid_duration_minutes", nullable = false) private int minimumValidDurationMinutes;
    @Column(name = "maximum_session_duration_minutes", nullable = false) private int maximumSessionDurationMinutes;
    @Column(name = "minimum_valid_sample_count", nullable = false) private long minimumValidSampleCount;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "therapy_plan_exercise_prescriptions",
            joinColumns = @JoinColumn(name = "therapy_plan_id"))
    @MapKeyColumn(name = "exercise_code", length = ExerciseCode.MAX_LENGTH, nullable = false)
    @Column(name = "daily_quantity", nullable = false)
    private Map<String, Integer> dailyExercisePrescriptions = new TreeMap<>();
    @Version @Column(nullable = false) private long version;

    protected TherapyPlanEntity() {}

    public static TherapyPlanEntity fromDomain(TherapyPlan plan) {
        var entity = new TherapyPlanEntity();
        entity.id = plan.id().value();
        entity.clinicalPatientId = plan.clinicalPatientId().value();
        entity.doctorSubject = plan.doctorSubject().value();
        entity.updateFrom(plan);
        return entity;
    }

    public void updateFrom(TherapyPlan plan) {
        if (!id.equals(plan.id().value()) || !clinicalPatientId.equals(plan.clinicalPatientId().value())
                || !doctorSubject.equals(plan.doctorSubject().value())) throw new IllegalArgumentException("Cannot change therapy plan identity or ownership");
        status = plan.status().name();
        configurationVersion = plan.configurationVersion();
        dailySessionLimit = plan.dailySessionLimit();
        dailyTargetDurationMinutes = plan.dailyTargetDurationMinutes();
        dailyHardSafetyLimitMinutes = plan.dailyHardSafetyLimitMinutes().isPresent() ? plan.dailyHardSafetyLimitMinutes().getAsInt() : null;
        minimumValidDurationMinutes = plan.minimumValidDurationMinutes();
        maximumSessionDurationMinutes = plan.maximumSessionDurationMinutes();
        minimumValidSampleCount = plan.minimumValidSampleCount();
        dailyExercisePrescriptions.clear();
        plan.dailyExercisePrescriptions().forEach(
                (exerciseCode, quantity) -> dailyExercisePrescriptions.put(exerciseCode.value(), quantity));
    }

    public TherapyPlan toDomain() {
        return TherapyPlan.rehydrate(new TherapyPlanId(id), new ClinicalPatientId(clinicalPatientId),
                new CognitoSubject(doctorSubject), TherapyPlanStatus.valueOf(status), configurationVersion,
                dailySessionLimit, dailyTargetDurationMinutes, dailyHardSafetyLimitMinutes,
                minimumValidDurationMinutes, maximumSessionDurationMinutes, minimumValidSampleCount,
                dailyExercisePrescriptions.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> new ExerciseCode(entry.getKey()), Map.Entry::getValue)));
    }
}
