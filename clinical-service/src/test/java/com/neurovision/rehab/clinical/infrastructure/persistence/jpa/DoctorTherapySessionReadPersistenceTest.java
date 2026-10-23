package com.neurovision.rehab.clinical.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

/** Real repository SQL proof; HTTP, authentication and concurrent writers are not exercised. */
@QuarkusTest
@TestProfile(DoctorTherapySessionReadPersistenceTest.IsolatedPostgres.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorTherapySessionReadPersistenceTest {
    private static final Instant T=Instant.parse("2026-10-22T00:00:00Z");
    private static final LocalDate QUOTA_DATE=LocalDate.of(2026,10,21);
    private static final CognitoSubject DOCTOR=new CognitoSubject("synthetic-session-reader");
    private static final CognitoSubject OTHER=new CognitoSubject("synthetic-other-session-owner");
    private static final ClinicalPatientId PATIENT=new ClinicalPatientId(new UUID(1,1));
    private static final ClinicalPatientId ENDED=new ClinicalPatientId(new UUID(1,2));
    private static final ClinicalPatientId EMPTY=new ClinicalPatientId(new UUID(1,3));
    private static final ClinicalPatientId UNASSIGNED=new ClinicalPatientId(new UUID(1,4));
    private static final List<String> TABLES=List.of("clinical_patients","doctor_patient_assignments",
            "therapy_plans","therapy_plan_exercise_prescriptions","therapy_plan_revisions",
            "therapy_plan_revision_exercises","therapeutic_exercise_configurations","therapy_sessions",
            "therapy_session_progress","therapy_session_activity_receipts");
    private final Map<String,TherapySession> fixtures=new LinkedHashMap<>();

    @Inject AgroalDataSource dataSource;
    @Inject Flyway flyway;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject DoctorTherapySessionReadRepository reads;
    @Inject SessionActivityRepository activity;
    @Inject ObjectMapper mapper;

    @BeforeAll
    void verifyDedicatedDatabaseBeforeMigrating() throws SQLException {
        // Attest the actual connection before allowing Flyway or fixture writes.
        try(var connection=dataSource.getConnection();
                var statement=connection.prepareStatement("SELECT current_database(), current_user")) {
            statement.setQueryTimeout(5);
            try(var result=statement.executeQuery()) {
                assertTrue(result.next(),"Dedicated PostgreSQL identity unavailable");
                assertTrue(IsolatedPostgres.DATABASE.equals(result.getString(1))
                        && IsolatedPostgres.USER.equals(result.getString(2)),"Refusing a non-dedicated database or role");
            }
        }
        flyway.migrate();
    }

    @Test @TestTransaction @Timeout(60)
    void readsExactOwnedHistoryWithCombinedFiltersAndNoStoredChanges() {
        requireEmptyDatabase();
        seed();
        entities.flush();
        entities.clear();
        var before=snapshot();
        var query=new DoctorTherapySessionQueryService(reads);

        // Hand-authored expectations: equal start instants are ordered by UUID descending.
        page(query,null,null,null,null,0,2,7,"A","B");
        page(query,null,null,null,null,1,2,7,"D","C");
        page(query,null,null,null,null,2,2,7,"G","E");
        page(query,null,null,null,null,3,2,7,"F");
        page(query,null,null,null,null,4,2,7);
        page(query,null,null,null,null,0,100,7,"A","B","D","C","G","E","F");
        page(query,null,null,null,null,Integer.MAX_VALUE,1,7);

        page(query,"fixation",null,null,null,0,20,3,"A","B","G");
        page(query,"visual-scanning",null,null,null,0,20,2,"D","C");
        page(query,"visual-search",null,null,null,0,20,2,"E","F");
        page(query,null,"ACTIVE",null,null,0,20,1,"A");
        page(query,null,"COMPLETED",null,null,0,20,3,"B","G","F");
        page(query,null,"CANCELLED",null,null,0,20,1,"C");
        page(query,null,"ABANDONED",null,null,0,20,1,"D");
        page(query,null,"FAILED_TECHNICAL",null,null,0,20,1,"E");

        page(query,null,null,T.toString(),T.plusSeconds(120).toString(),0,20,3,"B","D","C");
        page(query,null,null,T.minusSeconds(60).toString(),T.toString(),0,20,2,"G","E");
        page(query,null,null,T.toString(),null,0,20,4,"A","B","D","C");
        page(query,null,null,null,T.toString(),0,20,3,"G","E","F");
        page(query,"fixation","COMPLETED",T.minusSeconds(60).toString(),T.toString(),0,20,1,"G");
        page(query,"visual-scanning","CANCELLED",T.toString(),T.plusSeconds(1).toString(),0,20,1,"C");
        page(query,"visual-search","ACTIVE",null,null,0,20,0);
        page(query,null,null,T.plusSeconds(121).toString(),T.plusSeconds(122).toString(),0,20,0);
        page(query,null,null,T.toString(),T.plusSeconds(1).toString(),0,1,2,"D");
        page(query,null,null,T.toString(),T.plusSeconds(1).toString(),1,1,2,"C");
        page(query,null,null,T.toString(),T.plusSeconds(1).toString(),2,1,2);
        page(query,"fixation","COMPLETED","2026-10-21T18:59:00-05:00","2026-10-21T19:00:00-05:00",0,20,1,"G");

        var empty=query.list(DOCTOR.value(),EMPTY.value(),0,20,null,null,null,null);
        assertTrue(empty.clinicalPatientId().equals(EMPTY.value()),"Wrong empty-page patient");
        assertEquals(0,empty.total()); assertTrue(empty.sessions().isEmpty());
        for(var denied:List.of(ENDED.value(),UNASSIGNED.value(),new UUID(1,99)))
            assertThrows(SecurityException.class,()->query.list(DOCTOR.value(),denied,0,20,null,null,null,null));

        var other=query.list(OTHER.value(),PATIENT.value(),0,20,null,null,null,null);
        assertEquals(1,other.total());
        assertEquals(List.of("FOREIGN"),aliases(other));

        // Read all states and exercises, including sessions from suspended/cancelled plans.
        for(String alias:List.of("A","B","C","D","E","F","G")) detail(query,DOCTOR,alias);
        detail(query,OTHER,"FOREIGN");
        var knownSession=fixtures.get("A").id().value();
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),PATIENT.value(),new UUID(0,999)));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),new UUID(1,99),knownSession));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),EMPTY.value(),knownSession));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),PATIENT.value(),fixtures.get("FOREIGN").id().value()));
        assertThrows(SecurityException.class,()->query.detail(OTHER.value(),PATIENT.value(),knownSession));
        for(String alias:List.of("ENDED","UNASSIGNED")) {
            var denied=fixtures.get(alias);
            assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),denied.clinicalPatientId().value(),denied.id().value()));
        }
        entities.flush();
        assertTrue(before.equals(snapshot()),"Session history/detail reads changed persisted data");

        // Revoke just this doctor's assignment, then attest that subsequent reads fail closed.
        var assignment=assignments.findActiveByClinicalPatientIdAndDoctorSubject(PATIENT,DOCTOR).orElseThrow();
        assignment.end(T.plusSeconds(3600));
        assignments.save(assignment);
        entities.flush(); entities.clear();
        var revoked=snapshot();
        assertThrows(SecurityException.class,()->query.list(DOCTOR.value(),PATIENT.value(),0,20,null,null,null,null));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),PATIENT.value(),knownSession));
        assertEquals(List.of("FOREIGN"),aliases(query.list(OTHER.value(),PATIENT.value(),0,20,null,null,null,null)));
        detail(query,OTHER,"FOREIGN");
        assertTrue(revoked.equals(snapshot()),"Revoked/remaining-owner reads changed persisted data");
        // TestTransaction rolls back all fixtures, including append-only configurations.
    }

    @Test @TestTransaction @Timeout(60)
    void readsFrozenTechnicalProgressAndRejectsCorruptionWithoutStoredChanges() throws Exception {
        requireEmptyDatabase();
        seed();
        var repetitions=CompletionTarget.Criterion.REPETITIONS;
        var time=CompletionTarget.Criterion.ACTIVE_TIME;
        var empty=progressSession(201,repetitions,null,TherapySessionStatus.ACTIVE);
        var partial=progressSession(202,repetitions,creditedProgress(4200,2),TherapySessionStatus.ACTIVE);
        var reached=progressSession(203,repetitions,creditedProgress(5000,5),TherapySessionStatus.ACTIVE);
        var completed=progressSession(204,repetitions,creditedProgress(5000,5),TherapySessionStatus.COMPLETED);
        var abandoned=progressSession(205,repetitions,creditedProgress(4200,2),TherapySessionStatus.ABANDONED);
        var timePartial=progressSession(206,time,creditedProgress(4999,0),TherapySessionStatus.ACTIVE);
        var timeCompleted=progressSession(207,time,creditedProgress(5000,0),TherapySessionStatus.COMPLETED);
        entities.flush(); entities.clear();
        var before=snapshot();
        var query=new DoctorTherapySessionQueryService(reads);

        assertProgress(query,empty,0,0,false);
        assertProgress(query,partial,4200,2,false);
        assertProgress(query,reached,5000,5,true); // Reaching the target must not change ACTIVE to COMPLETED.
        assertProgress(query,completed,5000,5,true);
        assertProgress(query,abandoned,4200,2,false);
        assertProgress(query,timePartial,4999,0,false);
        assertProgress(query,timeCompleted,5000,0,true);
        for(String alias:List.of("A","B","C","D","E","F","G")) {
            var source=fixtures.get(alias);
            var progress=query.detail(DOCTOR.value(),source.clinicalPatientId().value(),source.id().value()).technicalProgress();
            assertEquals("NOT_APPLICABLE",progress.availability().name());
            assertNull(progress.criterion()); assertNull(progress.target()); assertNull(progress.activeMilliseconds());
            assertNull(progress.completedRepetitions()); assertNull(progress.targetReached());
        }
        assertThrows(SecurityException.class,()->query.detail(OTHER.value(),partial.clinicalPatientId().value(),partial.id().value()));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),PATIENT.value(),partial.id().value()));
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),partial.clinicalPatientId().value(),new UUID(0,999)));
        entities.flush();
        assertTrue(before.equals(snapshot()),"Successful and denied progress reads changed stored data or receipts");

        // JSONB accepts objects that are not valid application progress; the read must fail closed.
        String valid=mapper.writeValueAsString(creditedProgress(4200,2));
        var missing=mapper.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("completedRepetitions");
        for(String invalid:List.of("{}",missing.toString())) {
            replaceProgressJson(partial,invalid);
            var corrupt=snapshot();
            assertThrows(IllegalStateException.class,()->query.detail(DOCTOR.value(),partial.clinicalPatientId().value(),partial.id().value()));
            assertThrows(SecurityException.class,()->query.detail(OTHER.value(),partial.clinicalPatientId().value(),partial.id().value()));
            entities.flush();
            assertTrue(corrupt.equals(snapshot()),"Rejected corrupt-state read changed stored data");
        }
        replaceProgressJson(partial,valid);
        assertTrue(before.equals(snapshot()),"Synthetic state restoration must preserve the original fixture");

        var assignment=assignments.findActiveByClinicalPatientIdAndDoctorSubject(partial.clinicalPatientId(),DOCTOR).orElseThrow();
        assignment.end(T.plusSeconds(3600)); assignments.save(assignment);
        entities.flush(); entities.clear();
        var revoked=snapshot();
        assertThrows(SecurityException.class,()->query.detail(DOCTOR.value(),partial.clinicalPatientId().value(),partial.id().value()));
        assertProgress(query,completed,5000,5,true);
        assertTrue(revoked.equals(snapshot()),"Revoked-assignment progress read changed stored data");
        // TestTransaction rolls back the synthetic sessions, progress, receipts and assignment changes.
    }

    private void assertProgress(DoctorTherapySessionQueryService query,TherapySession source,
            long milliseconds,long repetitions,boolean reached) {
        var detail=query.detail(DOCTOR.value(),source.clinicalPatientId().value(),source.id().value());
        var progress=detail.technicalProgress();
        var frozen=(Prescribed)source.configurationParameters();
        assertEquals("AVAILABLE",progress.availability().name());
        assertEquals(frozen.completionTarget().criterion(),progress.criterion()); assertEquals(5L,progress.target());
        assertEquals(milliseconds,progress.activeMilliseconds()); assertEquals(repetitions,progress.completedRepetitions());
        assertEquals(reached,progress.targetReached());
        assertEquals(source.status().name(),detail.session().status());
        assertEquals(source.endedAt().orElse(null),detail.session().endedAt());
        assertEquals(source.activeDurationSeconds(),detail.session().activeDurationSeconds());
        assertEquals(1L,detail.session().configurationVersion(),"Must not substitute the newer version 9 goal");
        assertEquals(2,detail.session().parameterSchemaVersion());
        assertEquals(source.configurationParameters(),detail.configurationParameters());
    }

    private SessionActivityProgress creditedProgress(long milliseconds,int repetitions) {
        var state=SessionActivityProgress.empty();
        state=state.accept(new SessionActivityEvent(1,new UUID(8,1),0,SessionActivityEvent.Type.ACTIVITY_STARTED,null,null),T);
        for(int i=1;i<=repetitions;i++) {
            var id=new UUID(9,i);
            state=state.accept(new SessionActivityEvent(1,new UUID(8,state.nextSequence()+1),state.nextSequence(),
                    SessionActivityEvent.Type.REPETITION_STARTED,id,i),T);
            state=state.accept(new SessionActivityEvent(1,new UUID(8,state.nextSequence()+1),state.nextSequence(),
                    SessionActivityEvent.Type.REPETITION_FINISHED,id,i),T);
        }
        return state.accept(new SessionActivityEvent(1,new UUID(8,state.nextSequence()+1),state.nextSequence(),
                SessionActivityEvent.Type.HEARTBEAT,null,null),T.plusMillis(milliseconds));
    }

    private TherapySession progressSession(long number,CompletionTarget.Criterion criterion,
            SessionActivityProgress progress,TherapySessionStatus status) {
        // Separate patients preserve the real one-ACTIVE-session-per-patient constraint.
        var patient=new ClinicalPatientId(new UUID(7,number));
        patients.save(ClinicalPatient.registerInvited(patient,new CognitoSubject("synthetic-progress-reader-"+number),T.minusSeconds(2000)));
        assignment(patient,DOCTOR,number,false);
        var plan=TherapyPlan.rehydrate(new TherapyPlanId(new UUID(3,number)),patient,DOCTOR,TherapyPlanStatus.ACTIVE,
                9,10,30,null,1,15,10,Map.of(TherapeuticExercise.FIXATION.code(),10));
        plans.save(plan);
        var parameters=new Prescribed(TherapeuticConfigurationTestData.fixationSnapshot().parameters(),new CompletionTarget(criterion,5));
        var frozen=TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(4,number)),plan.id(),
                TherapeuticExercise.FIXATION,1,2,T.minusSeconds(1000),DOCTOR,T.minusSeconds(1000),parameters);
        configurations.append(frozen);
        configurations.append(TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(5,number)),plan.id(),
                TherapeuticExercise.FIXATION,9,2,T.plusSeconds(1000),DOCTOR,T.plusSeconds(1000),
                new Prescribed(new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.RIGHT,600,1500,60,12,900,3),
                        new CompletionTarget(criterion,6))));
        boolean active=status==TherapySessionStatus.ACTIVE;
        long seconds=progress==null?0:progress.activeMilliseconds()/1000;
        var value=TherapySession.rehydrate(new TherapySessionId(new UUID(0,number)),plan.id(),patient,DOCTOR,
                TherapeuticExercise.FIXATION.code(),TherapeuticConfigurationSnapshot.from(frozen),QUOTA_DATE,
                new IdempotencyKey("progress-detail-"+number),"b".repeat(64),1,15,10,status,seconds,0,
                active || status==TherapySessionStatus.COMPLETED,T,active?T.plusSeconds(120):null,
                active?null:T.plusSeconds(10),status==TherapySessionStatus.ABANDONED?"Synthetic abandonment":null);
        sessions.save(value);
        if(progress!=null) {
            entities.flush();
            sessions.findByIdForUpdate(value.id()).orElseThrow();
            activity.save(value.id(),progress);
            var last=new SessionActivityEvent(1,new UUID(8,progress.nextSequence()),progress.nextSequence()-1,
                    SessionActivityEvent.Type.HEARTBEAT,null,null);
            var acknowledgement=new PatientSessionProgressUseCase.View(value.id().value(),"ACTIVE",criterion,5,
                    progress.activeMilliseconds(),progress.completedRepetitions(),parameters.completionTarget().reached(progress),
                    progress.nextSequence(),progress.mode(),null);
            activity.append(value.id(),new SessionActivityRepository.Receipt(last,acknowledgement));
        }
        return value;
    }

    private void replaceProgressJson(TherapySession session,String json) {
        assertEquals(1,entities.createNativeQuery("UPDATE therapy_session_progress SET state = CAST(:state AS jsonb) WHERE therapy_session_id = :session")
                .setParameter("state",json).setParameter("session",session.id().value()).executeUpdate());
        entities.clear();
    }

    private void detail(DoctorTherapySessionQueryService query,CognitoSubject doctor,String alias) {
        var source=fixtures.get(alias);
        var result=query.detail(doctor.value(),source.clinicalPatientId().value(),source.id().value());
        var actual=result.session();
        assertTrue(source.clinicalPatientId().value().equals(result.clinicalPatientId()),"Wrong detail patient");
        assertTrue(source.id().value().equals(actual.therapySessionId()),"Wrong detail session");
        assertTrue(source.therapyPlanId().value().equals(actual.therapyPlanId()),"Wrong detail plan");
        assertEquals(source.exerciseCode().value(),actual.exerciseCode());
        assertEquals(source.status().name(),actual.status());
        assertEquals(source.startedAt(),actual.startedAt());
        assertEquals(source.endedAt().orElse(null),actual.endedAt());
        assertEquals(source.activeDurationSeconds(),actual.activeDurationSeconds());
        assertEquals(QUOTA_DATE,actual.quotaDate());
        assertTrue(source.therapeuticConfigurationId().value().equals(actual.configurationId()),"Wrong detail frozen configuration");
        assertEquals(1,actual.configurationVersion(),"Must not substitute the newer configuration version 9");
        assertEquals(source.parameterSchemaVersion(),actual.parameterSchemaVersion());
        assertEquals(source.configurationParameters(),result.configurationParameters(),"Must preserve frozen parameters, not version 9 parameters");
        assertEquals(source.minimumValidDurationMinutes(),result.sessionLimits().minimumValidDurationMinutes());
        assertEquals(source.maximumSessionDurationMinutes(),result.sessionLimits().maximumSessionDurationMinutes());
        assertEquals(source.minimumValidSampleCount(),result.sessionLimits().minimumValidSampleCount());
    }

    private void page(DoctorTherapySessionQueryService query,String exercise,String status,String from,String to,
            int page,int size,long total,String... expected) {
        var result=query.list(DOCTOR.value(),PATIENT.value(),page,size,exercise,status,from,to);
        assertTrue(PATIENT.value().equals(result.clinicalPatientId()),"Wrong history patient");
        assertEquals(page,result.page()); assertEquals(size,result.size()); assertEquals(total,result.total());
        assertEquals(List.of(expected),aliases(result));
        for(int i=0;i<expected.length;i++) {
            var source=fixtures.get(expected[i]); var actual=result.sessions().get(i);
            assertTrue(source.id().value().equals(actual.therapySessionId()),"Wrong session reference");
            assertTrue(source.therapyPlanId().value().equals(actual.therapyPlanId()),"Wrong plan reference");
            assertTrue(source.therapeuticConfigurationId().value().equals(actual.configurationId()),"Wrong frozen configuration");
            assertEquals(1,actual.configurationVersion(),"Must not substitute the newer configuration version 9");
            assertEquals(source.parameterSchemaVersion(),actual.parameterSchemaVersion());
            assertEquals(source.exerciseCode().value(),actual.exerciseCode());
            assertEquals(source.status().name(),actual.status());
            assertEquals(source.startedAt(),actual.startedAt());
            assertEquals(source.endedAt().orElse(null),actual.endedAt());
            assertEquals(source.activeDurationSeconds(),actual.activeDurationSeconds());
            assertEquals(QUOTA_DATE,actual.quotaDate());
        }
    }

    private List<String> aliases(DoctorTherapySessionQueryService.Page page) {
        return page.sessions().stream().map(row->fixtures.entrySet().stream()
                .filter(entry->entry.getValue().id().value().equals(row.therapySessionId()))
                .map(Map.Entry::getKey).findFirst().orElse("UNEXPECTED_SESSION")).toList();
    }

    private void seed() {
        for(var patient:List.of(PATIENT,ENDED,EMPTY,UNASSIGNED))
            patients.save(ClinicalPatient.registerInvited(patient,
                    new CognitoSubject("synthetic-session-patient-"+patient.value().getLeastSignificantBits()),T.minusSeconds(2000)));
        assignment(PATIENT,DOCTOR,1,false); assignment(PATIENT,OTHER,2,false);
        assignment(ENDED,DOCTOR,3,true); assignment(EMPTY,DOCTOR,4,false);
        var fixation=plan(1,PATIENT,DOCTOR,TherapyPlanStatus.ACTIVE,TherapeuticExercise.FIXATION);
        var scanning=plan(2,PATIENT,DOCTOR,TherapyPlanStatus.SUSPENDED,TherapeuticExercise.VISUAL_SCANNING);
        var search=plan(3,PATIENT,DOCTOR,TherapyPlanStatus.CANCELLED,TherapeuticExercise.VISUAL_SEARCH);
        var foreign=plan(4,PATIENT,OTHER,TherapyPlanStatus.ACTIVE,TherapeuticExercise.FIXATION);
        var ended=plan(5,ENDED,DOCTOR,TherapyPlanStatus.ACTIVE,TherapeuticExercise.FIXATION);
        var unassigned=plan(6,UNASSIGNED,DOCTOR,TherapyPlanStatus.ACTIVE,TherapeuticExercise.FIXATION);
        session("A",10,fixation,TherapySessionStatus.ACTIVE,120,17);
        session("B",20,fixation,TherapySessionStatus.COMPLETED,60,61);
        session("C",30,scanning,TherapySessionStatus.CANCELLED,0,23);
        session("D",40,scanning,TherapySessionStatus.ABANDONED,0,19);
        session("E",50,search,TherapySessionStatus.FAILED_TECHNICAL,-60,11);
        session("F",60,search,TherapySessionStatus.COMPLETED,-120,71);
        session("G",70,fixation,TherapySessionStatus.COMPLETED,-60,91);
        session("FOREIGN",80,foreign,TherapySessionStatus.COMPLETED,180,67);
        session("ENDED",90,ended,TherapySessionStatus.COMPLETED,240,67);
        session("UNASSIGNED",100,unassigned,TherapySessionStatus.COMPLETED,300,67);
    }

    private void assignment(ClinicalPatientId patient,CognitoSubject doctor,long number,boolean ended) {
        var value=DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(new UUID(2,number)),patient,doctor,T.minusSeconds(1500));
        if(ended) value.end(T.plusSeconds(1000));
        assignments.save(value);
    }

    private BoundPlan plan(long number,ClinicalPatientId patient,CognitoSubject doctor,TherapyPlanStatus status,TherapeuticExercise exercise) {
        var plan=TherapyPlan.rehydrate(new TherapyPlanId(new UUID(3,number)),patient,doctor,status,
                9,10,30,null,1,15,10,Map.of(exercise.code(),10));
        plans.save(plan);
        var old=configuration(plan,exercise,new UUID(4,number),1,T.minusSeconds(1000));
        configurations.append(old);
        configurations.append(configuration(plan,exercise,new UUID(5,number),9,T.plusSeconds(1000)));
        return new BoundPlan(plan,old);
    }

    private TherapeuticExerciseConfiguration configuration(TherapyPlan plan,TherapeuticExercise exercise,UUID id,long version,Instant at) {
        var parameters=switch(exercise) {
            case FIXATION -> TherapeuticConfigurationTestData.fixationSnapshot().parameters();
            case VISUAL_SEARCH -> TherapeuticConfigurationTestData.visualSearchSnapshot().parameters();
            case VISUAL_SCANNING -> new VisualScanning(VisualScanningMode.STANDARD_SCANNING,AffectedHemifield.LEFT,
                    300,2000,5,30,5,500,1);
        };
        // The newer version deliberately differs, so substituting its payload cannot pass detail assertions.
        if(version==9) parameters=switch(exercise) {
            case FIXATION -> new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.RIGHT,
                    600,1500,60,12,900,3);
            case VISUAL_SEARCH -> new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,AffectedHemifield.RIGHT,
                    15,3,600,6000,new BigDecimal("0.8"),60,4);
            case VISUAL_SCANNING -> new VisualScanning(VisualScanningMode.COMPENSATORY_SACCADE,AffectedHemifield.RIGHT,
                    600,3000,10,60,10,1000,2);
        };
        return TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id),plan.id(),exercise,
                version,1,at,plan.doctorSubject(),at,parameters);
    }

    private void session(String alias,long number,BoundPlan bound,TherapySessionStatus status,long offset,long activeSeconds) {
        var plan=bound.plan(); var configuration=bound.frozen(); var start=T.plusSeconds(offset);
        boolean active=status==TherapySessionStatus.ACTIVE;
        String reason=active || status==TherapySessionStatus.COMPLETED?null:"Synthetic terminal reason";
        var value=TherapySession.rehydrate(new TherapySessionId(new UUID(0,number)),plan.id(),plan.clinicalPatientId(),
                plan.doctorSubject(),configuration.exercise().code(),TherapeuticConfigurationSnapshot.from(configuration),
                QUOTA_DATE,new IdempotencyKey("session-history-"+alias),"a".repeat(64),1,15,10,
                status,activeSeconds,10,active || status==TherapySessionStatus.COMPLETED,
                start,active?start.plusSeconds(30):null,active?null:start.plusSeconds(600),reason);
        sessions.save(value);
        fixtures.put(alias,value);
    }

    private void requireEmptyDatabase() {
        for(var table:TABLES) {
            var count=(Number)entities.createNativeQuery("SELECT count(*) FROM "+table).getSingleResult();
            assertTrue(count.longValue()==0,"Refusing nonempty synthetic test database");
        }
    }

    private Map<String,List<?>> snapshot() {
        var result=new LinkedHashMap<String,List<?>>();
        for(var table:TABLES) {
            List<?> rows=entities.createNativeQuery(
                    "SELECT CAST(to_jsonb(t) AS text) FROM "+table+" t ORDER BY CAST(to_jsonb(t) AS text)").getResultList();
            result.put(table,List.copyOf(rows));
        }
        return result;
    }

    private record BoundPlan(TherapyPlan plan,TherapeuticExerciseConfiguration frozen) {}

    /** Explicit dedicated connection only; never fall back to Compose or auto-start services. */
    public static class IsolatedPostgres implements QuarkusTestProfile {
        static final String DATABASE="nv_clinical_session_history_test";
        static final String USER="clinical_session_history_test";
        @Override public Map<String,String> getConfigOverrides() {
            String url=System.getProperty("clinical.session-history-test.jdbc-url","");
            if(!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/"+DATABASE))
                throw new IllegalStateException("Explicit isolated session-history PostgreSQL URL required");
            int port=Integer.parseInt(url.substring(url.lastIndexOf(':')+1,url.lastIndexOf('/')));
            if(port<1 || port>65535) throw new IllegalStateException("Invalid isolated PostgreSQL port");
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.jdbc.url",url+"?connectTimeout=5&socketTimeout=5"),
                    Map.entry("quarkus.datasource.username",USER),
                    Map.entry("quarkus.datasource.password","synthetic-session-history-test-only"),
                    Map.entry("quarkus.devservices.enabled","false"),
                    Map.entry("quarkus.datasource.devservices.enabled","false"),
                    Map.entry("quarkus.kafka.devservices.enabled","false"),
                    Map.entry("quarkus.http.test-port","0"),
                    Map.entry("mp.messaging.incoming.patient-account-invited.enabled","false"),
                    Map.entry("quarkus.oidc.enabled","false"),
                    Map.entry("quarkus.hibernate-orm.schema-management.strategy","none"),
                    Map.entry("quarkus.flyway.migrate-at-start","false"),
                    Map.entry("quarkus.flyway.clean-disabled","true"),
                    Map.entry("quarkus.datasource.jdbc.acquisition-timeout","5S"));
        }
    }
}
