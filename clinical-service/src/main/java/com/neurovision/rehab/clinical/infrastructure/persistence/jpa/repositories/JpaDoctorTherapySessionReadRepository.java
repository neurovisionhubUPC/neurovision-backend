package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class JpaDoctorTherapySessionReadRepository implements DoctorTherapySessionReadRepository {
    private final EntityManager entities;
    private final TherapeuticConfigurationJsonCodec codec;
    public JpaDoctorTherapySessionReadRepository(EntityManager entities, TherapeuticConfigurationJsonCodec codec) {
        this.entities=Objects.requireNonNull(entities);
        this.codec=Objects.requireNonNull(codec);
    }

    @Override public Optional<Detail> observeDetail(UUID patient,String doctor,UUID session) {
        var query=entities.createNativeQuery("""
                SELECT s.clinical_patient_id, s.id, s.therapy_plan_id, s.exercise_code, s.status,
                  s.started_at, s.ended_at, s.active_duration_seconds, s.quota_date,
                  s.therapeutic_configuration_id, s.configuration_version, s.parameter_schema_version,
                  s.therapeutic_exercise, CAST(s.configuration_parameters AS text),
                  s.minimum_valid_duration_minutes, s.maximum_session_duration_minutes, s.minimum_valid_sample_count
                FROM therapy_sessions s
                WHERE s.id = :session AND s.clinical_patient_id = :patient AND s.doctor_cognito_sub = :doctor
                  AND EXISTS (SELECT 1 FROM doctor_patient_assignments a JOIN clinical_patients p ON p.id = a.clinical_patient_id
                    WHERE a.clinical_patient_id = s.clinical_patient_id AND a.doctor_cognito_sub = :doctor AND a.status = 'ACTIVE')
                LIMIT 2
                """).setParameter("patient",patient).setParameter("doctor",doctor).setParameter("session",session);
        @SuppressWarnings("unchecked") List<Object[]> rows=query.getResultList();
        if(rows.isEmpty()) return Optional.empty();
        if(rows.size()!=1 || rows.getFirst().length!=17) throw new IllegalStateException("Invalid session detail observation");
        Object[] r=rows.getFirst();
        var summary=new Session((UUID)r[1],(UUID)r[2],(String)r[3],TherapySessionStatus.valueOf((String)r[4]).name(),
                instant(r[5]),r[6]==null?null:instant(r[6]),((Number)r[7]).longValue(),date(r[8]),
                (UUID)r[9],((Number)r[10]).longValue(),((Number)r[11]).intValue());
        var parameters=codec.decode(TherapeuticExercise.valueOf((String)r[12]),summary.parameterSchemaVersion(),(String)r[13]);
        return Optional.of(new Detail((UUID)r[0],summary,parameters,
                new SessionLimits(((Number)r[14]).intValue(),((Number)r[15]).intValue(),((Number)r[16]).longValue())));
    }

    @Override public Observation observe(UUID patient,String doctor,Filters filters,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid page");
        String predicate="s.clinical_patient_id = :patient AND s.doctor_cognito_sub = :doctor AND (SELECT allowed FROM authorized)";
        if(filters.exerciseCode()!=null) predicate+=" AND s.exercise_code = :exercise";
        if(filters.status()!=null) predicate+=" AND s.status = :status";
        if(filters.from()!=null) predicate+=" AND s.started_at >= :from";
        if(filters.to()!=null) predicate+=" AND s.started_at < :to";
        String sql="""
                WITH authorized AS (
                  SELECT EXISTS (SELECT 1 FROM doctor_patient_assignments a
                    JOIN clinical_patients p ON p.id = a.clinical_patient_id
                    WHERE a.clinical_patient_id = :patient AND a.doctor_cognito_sub = :doctor
                      AND a.status = 'ACTIVE') AS allowed
                ), filtered AS (
                  SELECT s.id, s.therapy_plan_id, s.exercise_code, s.status, s.started_at, s.ended_at,
                    s.active_duration_seconds, s.quota_date, s.therapeutic_configuration_id,
                    s.configuration_version, s.parameter_schema_version
                  FROM therapy_sessions s WHERE
                """ + predicate + """
                ), page AS (
                  SELECT * FROM filtered ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset
                )
                SELECT a.allowed, (SELECT count(*) FROM filtered), p.id, p.therapy_plan_id,
                  p.exercise_code, p.status, p.started_at, p.ended_at, p.active_duration_seconds,
                  p.quota_date, p.therapeutic_configuration_id, p.configuration_version, p.parameter_schema_version
                FROM authorized a LEFT JOIN page p ON true ORDER BY p.started_at DESC, p.id DESC
                """;
        var query=entities.createNativeQuery(sql).setParameter("patient",patient).setParameter("doctor",doctor)
                .setParameter("limit",limit).setParameter("offset",offset);
        if(filters.exerciseCode()!=null) query.setParameter("exercise",filters.exerciseCode());
        if(filters.status()!=null) query.setParameter("status",filters.status().name());
        if(filters.from()!=null) query.setParameter("from",filters.from());
        if(filters.to()!=null) query.setParameter("to",filters.to());
        @SuppressWarnings("unchecked") List<Object[]> rows=query.getResultList();
        if(rows.isEmpty() || rows.size()>limit) throw new IllegalStateException("Invalid history observation");
        Object[] first=rows.getFirst();
        boolean allowed=(Boolean)first[0];
        long total=((Number)first[1]).longValue();
        var sessions=new ArrayList<Session>();
        for(Object[] row:rows) {
            if(!Objects.equals(first[0],row[0]) || ((Number)row[1]).longValue()!=total)
                throw new IllegalStateException("Inconsistent history observation");
            if(row[2]==null) {
                if(rows.size()!=1) throw new IllegalStateException("Invalid empty page");
                continue;
            }
            sessions.add(new Session((UUID)row[2],(UUID)row[3],(String)row[4],
                    TherapySessionStatus.valueOf((String)row[5]).name(),instant(row[6]),
                    row[7]==null?null:instant(row[7]),((Number)row[8]).longValue(),date(row[9]),
                    (UUID)row[10],((Number)row[11]).longValue(),((Number)row[12]).intValue()));
        }
        if(!allowed && (total!=0 || !sessions.isEmpty())) throw new IllegalStateException("Invalid denied page");
        return new Observation(allowed,sessions,total);
    }

    private static Instant instant(Object value) {
        if(value instanceof Instant instant) return instant;
        if(value instanceof OffsetDateTime offset) return offset.toInstant();
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported timestamp");
    }
    private static LocalDate date(Object value) {
        if(value instanceof LocalDate date) return date;
        if(value instanceof java.sql.Date date) return date.toLocalDate();
        throw new IllegalStateException("Unsupported quota date");
    }
}
