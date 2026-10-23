package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.SessionActivityProgress;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class JpaDoctorTherapySessionReadRepository implements DoctorTherapySessionReadRepository {
    private final EntityManager entities;
    private final TherapeuticConfigurationJsonCodec codec;
    private final ObjectMapper mapper;
    public JpaDoctorTherapySessionReadRepository(EntityManager entities, TherapeuticConfigurationJsonCodec codec, ObjectMapper mapper) {
        this.entities=Objects.requireNonNull(entities);
        this.codec=Objects.requireNonNull(codec);
        this.mapper=Objects.requireNonNull(mapper);
    }

    @Override public Optional<Detail> observeDetail(UUID patient,String doctor,UUID session) {
        var query=entities.createNativeQuery("""
                SELECT s.clinical_patient_id, s.id, s.therapy_plan_id, s.exercise_code, s.status,
                  s.started_at, s.ended_at, s.active_duration_seconds, s.quota_date,
                  s.therapeutic_configuration_id, s.configuration_version, s.parameter_schema_version,
                  s.therapeutic_exercise, CAST(s.configuration_parameters AS text),
                  s.minimum_valid_duration_minutes, s.maximum_session_duration_minutes, s.minimum_valid_sample_count,
                  progress.therapy_session_id, CAST(progress.state AS text)
                FROM therapy_sessions s
                LEFT JOIN therapy_session_progress progress ON progress.therapy_session_id = s.id
                WHERE s.id = :session AND s.clinical_patient_id = :patient AND s.doctor_cognito_sub = :doctor
                  AND EXISTS (SELECT 1 FROM doctor_patient_assignments a JOIN clinical_patients p ON p.id = a.clinical_patient_id
                    WHERE a.clinical_patient_id = s.clinical_patient_id AND a.doctor_cognito_sub = :doctor AND a.status = 'ACTIVE')
                LIMIT 2
                """).setParameter("patient",patient).setParameter("doctor",doctor).setParameter("session",session);
        @SuppressWarnings("unchecked") List<Object[]> rows=query.getResultList();
        if(rows.isEmpty()) return Optional.empty();
        if(rows.size()!=1 || rows.getFirst().length!=19) throw new IllegalStateException("Invalid session detail observation");
        Object[] r=rows.getFirst();
        var summary=new Session((UUID)r[1],(UUID)r[2],(String)r[3],TherapySessionStatus.valueOf((String)r[4]).name(),
                instant(r[5]),r[6]==null?null:instant(r[6]),((Number)r[7]).longValue(),date(r[8]),
                (UUID)r[9],((Number)r[10]).longValue(),((Number)r[11]).intValue());
        var parameters=codec.decode(TherapeuticExercise.valueOf((String)r[12]),summary.parameterSchemaVersion(),(String)r[13]);
        TechnicalProgress progress;
        if(parameters instanceof TherapeuticExerciseParameters.Prescribed prescribed) {
            if((r[17]==null)!=(r[18]==null) || (r[17]!=null && !summary.therapySessionId().equals(r[17])))
                throw new IllegalStateException("Invalid progress observation");
            var state=r[17]==null?SessionActivityProgress.empty():decodeProgress((String)r[18]);
            progress=TechnicalProgress.from(prescribed,state);
        } else {
            if(r[17]!=null || r[18]!=null) throw new IllegalStateException("Unexpected legacy progress");
            progress=TechnicalProgress.notApplicable();
        }
        return Optional.of(new Detail((UUID)r[0],summary,parameters,
                new SessionLimits(((Number)r[14]).intValue(),((Number)r[15]).intValue(),((Number)r[16]).longValue()),progress));
    }

    /** Validate persisted JSON explicitly: missing primitive fields must not deserialize as zero activity. */
    private SessionActivityProgress decodeProgress(String json) {
        try {
            JsonNode node=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(json);
            var fields=new HashSet<String>();
            if(node==null || !node.isObject()) throw new IllegalStateException("Invalid progress state");
            node.fieldNames().forEachRemaining(fields::add);
            if(!fields.equals(Set.of("nextSequence","activeMilliseconds","completedRepetitions","mode","anchor",
                    "repetitionId","repetitionIndex","usedRepetitions"))) throw new IllegalStateException("Invalid progress fields");
            for(String field:List.of("nextSequence","activeMilliseconds","completedRepetitions"))
                if(!node.get(field).isIntegralNumber() || !node.get(field).canConvertToLong())
                    throw new IllegalStateException("Invalid progress counter");
            if(!node.get("mode").isTextual() || (!node.get("anchor").isNull() && !node.get("anchor").isTextual() && !node.get("anchor").isNumber())
                    || (!node.get("repetitionId").isNull() && !node.get("repetitionId").isTextual())
                    || (!node.get("repetitionIndex").isNull() && (!node.get("repetitionIndex").isIntegralNumber()
                        || !node.get("repetitionIndex").canConvertToInt())) || !node.get("usedRepetitions").isArray())
                throw new IllegalStateException("Invalid progress representation");
            var used=new HashSet<UUID>();
            for(var id:node.get("usedRepetitions")) {
                if(!id.isTextual() || !used.add(progressUuid(id.textValue()))) throw new IllegalStateException("Invalid repetition history");
            }
            var state=new SessionActivityProgress(node.get("nextSequence").longValue(),node.get("activeMilliseconds").longValue(),
                    node.get("completedRepetitions").longValue(),SessionActivityProgress.Mode.valueOf(node.get("mode").textValue()),
                    node.get("anchor").isNull()?null:mapper.treeToValue(node.get("anchor"),Instant.class),
                    node.get("repetitionId").isNull()?null:progressUuid(node.get("repetitionId").textValue()),
                    node.get("repetitionIndex").isNull()?null:node.get("repetitionIndex").intValue(),used);
            if(state.mode()==SessionActivityProgress.Mode.NEW) {
                if(!state.equals(SessionActivityProgress.empty())) throw new IllegalStateException("Invalid initial progress");
            } else if(state.nextSequence()==0 || state.anchor()==null
                    || state.completedRepetitions()>(state.nextSequence()-1)/2 || state.completedRepetitions()>used.size()) {
                throw new IllegalStateException("Inconsistent activity progress");
            }
            if(state.repetitionId()!=null && (state.mode()!=SessionActivityProgress.Mode.ACTIVE
                    || !used.contains(state.repetitionId()) || state.repetitionIndex()<1
                    || state.completedRepetitions()==Long.MAX_VALUE || state.repetitionIndex().longValue()!=state.completedRepetitions()+1
                    || state.completedRepetitions()>=used.size())) throw new IllegalStateException("Inconsistent open repetition");
            return state;
        } catch(JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Stored progress unavailable",invalid);
        }
    }
    private static UUID progressUuid(String value) {
        var id=UUID.fromString(value);
        if(!id.toString().equals(value)) throw new IllegalStateException("Invalid repetition identifier");
        return id;
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
