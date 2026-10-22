package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapyPlanEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
@Transactional
public class JpaDoctorTherapyPlanReadRepository implements DoctorTherapyPlanReadRepository {
    private static final String ASSIGNMENT = """
            EXISTS (SELECT 1 FROM doctor_patient_assignments a
              WHERE a.clinical_patient_id = :patient AND a.doctor_cognito_sub = :doctor
                AND a.status = 'ACTIVE')
            """;
    private static final String SCOPE = "p.clinical_patient_id = :patient AND p.doctor_cognito_sub = :doctor AND " + ASSIGNMENT;
    private static final String STATUS = """
            COALESCE((SELECT r.plan_status FROM therapy_plan_revisions r
              WHERE r.therapy_plan_id = p.id AND r.effective_from <= :at AND r.plan_status IS NOT NULL
              ORDER BY r.sequence DESC LIMIT 1), p.status) = :status
            """;
    private final EntityManager entities;
    private final TherapyPlanRevisionRepository revisions;
    private final TherapeuticExerciseConfigurationRepository configurations;

    public JpaDoctorTherapyPlanReadRepository(EntityManager entities, TherapyPlanRevisionRepository revisions,
            TherapeuticExerciseConfigurationRepository configurations) {
        this.entities = Objects.requireNonNull(entities);
        this.revisions = Objects.requireNonNull(revisions);
        this.configurations = Objects.requireNonNull(configurations);
    }

    @Override public boolean hasActiveAssignment(UUID patient, String doctor) {
        return Boolean.TRUE.equals(scope(entities.createNativeQuery("SELECT " + ASSIGNMENT), patient, doctor).getSingleResult());
    }

    @Override public Page page(UUID patient, String doctor, TherapyPlanStatus status, Instant at, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page");
        String predicate = SCOPE + (status == null ? "" : " AND " + STATUS);
        var count = filtered(entities.createNativeQuery("SELECT count(*) FROM therapy_plans p WHERE " + predicate),
                patient, doctor, status, at);
        long total = ((Number) count.getSingleResult()).longValue();
        var query = filtered(entities.createNativeQuery("SELECT p.* FROM therapy_plans p WHERE " + predicate
                + " ORDER BY p.id ASC", TherapyPlanEntity.class), patient, doctor, status, at);
        query.setFirstResult(offset).setMaxResults(limit);
        @SuppressWarnings("unchecked") List<TherapyPlanEntity> rows = query.getResultList();
        return new Page(rows.stream().map(row -> effective(row, at)).toList(), total);
    }

    @Override public Optional<Detail> detail(UUID patient, UUID plan, String doctor, Instant at) {
        var query = scope(entities.createNativeQuery("SELECT p.* FROM therapy_plans p WHERE " + SCOPE
                + " AND p.id = :plan", TherapyPlanEntity.class), patient, doctor).setParameter("plan", plan).setMaxResults(1);
        @SuppressWarnings("unchecked") List<TherapyPlanEntity> rows = query.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        var current = effective(rows.getFirst(), at);
        var configs = new ArrayList<TherapeuticExerciseConfiguration>();
        for (var code : current.dailyExercisePrescriptions().keySet()) {
            configurations.findEffective(current.id(), TherapeuticExercise.from(code), at).ifPresent(configs::add);
        }
        return Optional.of(new Detail(current, configs));
    }

    private TherapyPlan effective(TherapyPlanEntity row, Instant at) {
        var base = row.toDomain();
        return TherapyPlanRevision.applyEligible(base, revisions.findEffective(base.id(), at), at);
    }
    private static Query scope(Query query, UUID patient, String doctor) {
        return query.setParameter("patient", patient).setParameter("doctor", doctor);
    }
    private static Query filtered(Query query, UUID patient, String doctor, TherapyPlanStatus status, Instant at) {
        scope(query, patient, doctor);
        if (status != null) query.setParameter("at", at).setParameter("status", status.name());
        return query;
    }
}
