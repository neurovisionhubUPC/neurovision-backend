package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "therapy_plan_revisions")
public class TherapyPlanRevisionEntity {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "therapy_plan_id", nullable = false, updatable = false) private UUID therapyPlanId;
    @Column(nullable = false, updatable = false) private long sequence;
    @Column(name = "command_id", nullable = false, updatable = false) private UUID commandId;
    @Column(name = "command_fingerprint", nullable = false, length = 64, updatable = false) private String commandFingerprint;
    @Column(name = "revision_kind", nullable = false, length = 16, updatable = false) private String kind;
    @Column(name = "effective_from", nullable = false, updatable = false) private Instant effectiveFrom;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "created_by_doctor_subject", nullable = false, length = 128, updatable = false) private String doctorSubject;
    @Column(name = "plan_status", length = 16, updatable = false) private String planStatus;
    @Column(name = "daily_session_limit", updatable = false) private Integer dailySessionLimit;
    @Column(name = "daily_target_duration_minutes", updatable = false) private Integer dailyTargetDurationMinutes;
    @Column(name = "daily_hard_safety_limit_minutes", updatable = false) private Integer dailyHardSafetyLimitMinutes;
    @Column(name = "has_exercise_distribution", nullable = false, updatable = false) private boolean hasExerciseDistribution;
    @Column(name = "clinical_reason", length = 500, updatable = false) private String clinicalReason;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "therapy_plan_revision_exercises",
            joinColumns = @JoinColumn(name = "therapy_plan_revision_id"))
    @MapKeyColumn(name = "exercise_code", length = ExerciseCode.MAX_LENGTH)
    @Column(name = "daily_quantity", nullable = false)
    private Map<String, Integer> dailyExercisePrescriptions = new TreeMap<>();

    protected TherapyPlanRevisionEntity() {}

    public static TherapyPlanRevisionEntity fromDomain(TherapyPlanRevision source) {
        var entity = new TherapyPlanRevisionEntity();
        entity.id = source.id().value();
        entity.therapyPlanId = source.therapyPlanId().value();
        entity.sequence = source.sequence();
        entity.commandId = source.commandId();
        entity.commandFingerprint = source.commandFingerprint();
        entity.kind = source.kind().name();
        entity.effectiveFrom = source.effectiveFrom();
        entity.createdAt = source.createdAt();
        entity.doctorSubject = source.createdByDoctorSubject().value();
        entity.planStatus = source.status().map(Enum::name).orElse(null);
        entity.dailySessionLimit = source.dailySessionLimit().isPresent() ? source.dailySessionLimit().getAsInt() : null;
        entity.dailyTargetDurationMinutes = source.dailyTargetDurationMinutes().isPresent()
                ? source.dailyTargetDurationMinutes().getAsInt() : null;
        entity.dailyHardSafetyLimitMinutes = source.dailyHardSafetyLimitMinutes().isPresent()
                ? source.dailyHardSafetyLimitMinutes().getAsInt() : null;
        entity.hasExerciseDistribution = source.dailyExercisePrescriptions().isPresent();
        source.dailyExercisePrescriptions().ifPresent(values -> values.forEach(
                (code, quantity) -> entity.dailyExercisePrescriptions.put(code.value(), quantity)));
        entity.clinicalReason = source.clinicalReason().orElse(null);
        return entity;
    }

    public TherapyPlanRevision toDomain() {
        Map<ExerciseCode, Integer> distribution = hasExerciseDistribution
                ? dailyExercisePrescriptions.entrySet().stream().collect(Collectors.toMap(
                        entry -> new ExerciseCode(entry.getKey()), Map.Entry::getValue))
                : null;
        return TherapyPlanRevision.rehydrate(new TherapyPlanRevisionId(id), new TherapyPlanId(therapyPlanId),
                sequence, commandId, commandFingerprint, TherapyPlanRevisionKind.valueOf(kind),
                effectiveFrom, createdAt, new CognitoSubject(doctorSubject),
                planStatus == null ? null : TherapyPlanStatus.valueOf(planStatus),
                dailySessionLimit, dailyTargetDurationMinutes, dailyHardSafetyLimitMinutes,
                distribution, clinicalReason);
    }
}
