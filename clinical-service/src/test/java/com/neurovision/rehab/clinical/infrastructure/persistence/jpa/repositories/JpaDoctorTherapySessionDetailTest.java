package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CompletionTarget;
import com.neurovision.rehab.clinical.domain.model.valueobjects.SessionActivityProgress;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JpaDoctorTherapySessionDetailTest {
    final JpaDoctorTherapySessionReadRepositoryTest fixture=new JpaDoctorTherapySessionReadRepositoryTest();
    final TherapeuticConfigurationJsonCodec codec=new TherapeuticConfigurationJsonCodec(new ObjectMapper());
    List<TherapeuticExerciseParameters> parameters() {
        return List.of(new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.CENTER,500,1000,50,10,800,2),
                new VisualScanning(VisualScanningMode.SYSTEMATIC_EXPLORATION,AffectedHemifield.LEFT,500,4000,15,50,10,1000,2),
                new VisualSearch(VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,AffectedHemifield.RIGHT,10,4,500,5000,new BigDecimal("0.75"),50,3));
    }
    Object[] row(TherapeuticExerciseParameters p) {
        return new Object[]{fixture.patient,fixture.session,fixture.plan,p.exercise().code().value(),"ACTIVE",fixture.start,null,
                17L,LocalDate.of(2026,10,22),fixture.configuration,2L,1,p.exercise().name(),codec.encode(p),1,10,25L,null,null};
    }
    @Test void oneReadRestrictsPatientSessionOwnerAndActiveAssignmentWithoutCurrentPlan() {
        fixture.rows=List.of();var repo=fixture.repository();
        assertTrue(repo.observeDetail(fixture.patient,"doctor",fixture.session).isEmpty());
        assertEquals(1,fixture.statements);assertEquals(1,fixture.executions);
        assertTrue(fixture.sql.contains("LEFT JOIN therapy_session_progress progress ON progress.therapy_session_id = s.id"));
        assertEquals(Map.of("patient",fixture.patient,"session",fixture.session,"doctor","doctor"),fixture.parameters);
        for(String part:List.of("s.id = :session","s.clinical_patient_id = :patient","s.doctor_cognito_sub = :doctor","a.doctor_cognito_sub = :doctor","a.clinical_patient_id = s.clinical_patient_id","a.status = 'ACTIVE'","EXISTS (SELECT 1","LIMIT 2"))
            assertTrue(fixture.sql.contains(part),part);
        for(String forbidden:List.of("therapy_plans","therapeutic_configurations","profile","idempotency_key","request_fingerprint","lease_expires_at","termination_reason","FOR UPDATE","UPDATE ","INSERT ","DELETE "))
            assertFalse(fixture.sql.contains(forbidden),forbidden);
    }
    @Test void mapsEachFrozenParameterTypeLimitsAndPersistedTimeline() {
        var mapper=new ObjectMapper().findAndRegisterModules();var repo=fixture.repository();
        for(var p:parameters()) {
            fixture.rows=Collections.singletonList(row(p));var detail=repo.observeDetail(fixture.patient,"doctor",fixture.session).orElseThrow();
            assertEquals(p,detail.configurationParameters());assertEquals(fixture.patient,detail.clinicalPatientId());
            assertEquals(2,detail.session().configurationVersion());assertEquals(1,detail.sessionLimits().minimumValidDurationMinutes());
            assertEquals(10,detail.sessionLimits().maximumSessionDurationMinutes());assertEquals(25,detail.sessionLimits().minimumValidSampleCount());
            assertEquals("NOT_APPLICABLE",detail.technicalProgress().availability().name());
            assertNull(detail.technicalProgress().targetReached());
            var json=com.neurovision.rehab.clinical.interfaces.rest.transform.DoctorTherapySessionReadRestAssembler.detail(detail,mapper);
            assertEquals(codec.encode(p),json.configurationParameters().toString());
        }
    }
    @Test void supportsDriverTimeTypesAndTerminalStatusesWithoutLeaseMutation() {
        var repo=fixture.repository();
        for(Object time:List.of(fixture.start,fixture.start.atOffset(ZoneOffset.ofHours(-5)),java.sql.Timestamp.from(fixture.start))) {
            for(String status:List.of("COMPLETED","CANCELLED","ABANDONED","FAILED_TECHNICAL")) {
                var r=row(parameters().getFirst());r[4]=status;r[5]=time;r[6]=time;r[8]=java.sql.Date.valueOf("2026-10-22");fixture.rows=Collections.singletonList(r);
                var result=repo.observeDetail(fixture.patient,"doctor",fixture.session).orElseThrow();
                assertEquals(fixture.start,result.session().startedAt());assertEquals(fixture.start,result.session().endedAt());
                assertEquals(status,result.session().status());assertEquals(17,result.session().activeDurationSeconds());
            }
        }
    }
    @Test void malformedUnsupportedAndMismatchedFrozenDataAreUnavailableNotBadRequest() {
        var service=new DoctorTherapySessionQueryService(fixture.repository());
        for(var replacement:List.of(new Object[]{13,"{"},new Object[]{13,"{}"},new Object[]{11,2},new Object[]{3,"visual-search"},new Object[]{14,0},new Object[]{16,0L})) {
            var r=row(parameters().getFirst());r[(Integer)replacement[0]]=replacement[1];fixture.rows=Collections.singletonList(r);
            assertThrows(IllegalStateException.class,()->service.detail("doctor",fixture.patient,fixture.session));
        }
        var r=row(parameters().getFirst());fixture.rows=List.of(r,r);
        assertThrows(IllegalStateException.class,()->service.detail("doctor",fixture.patient,fixture.session));
    }
    @Test void emptyObservationIsUniformDenialAndMismatchedObservationFailsClosed() {
        var service=new DoctorTherapySessionQueryService(fixture.repository());fixture.rows=List.of();
        assertThrows(SecurityException.class,()->service.detail("doctor",fixture.patient,fixture.session));
        var r=row(parameters().getFirst());r[0]=new UUID(0,99);fixture.rows=Collections.singletonList(r);
        assertThrows(IllegalStateException.class,()->service.detail("doctor",fixture.patient,fixture.session));
    }
    final ObjectMapper progressMapper=new ObjectMapper().findAndRegisterModules();
    SessionActivityProgress state(long milliseconds,int repetitions) {
        var used=new HashSet<UUID>();
        for(int i=0;i<repetitions;i++)used.add(new UUID(1,i+1));
        return new SessionActivityProgress(2L*repetitions+3,milliseconds,repetitions,
                SessionActivityProgress.Mode.ACTIVE,fixture.start,null,null,used);
    }
    Object[] progressRow(CompletionTarget.Criterion criterion,int target,SessionActivityProgress state) throws Exception {
        var r=row(new Prescribed(parameters().getFirst(),new CompletionTarget(criterion,target)));
        r[7]=state==null?0L:state.activeMilliseconds()/1000;r[11]=2;
        if(state!=null){r[17]=fixture.session;r[18]=progressMapper.writeValueAsString(state);}
        return r;
    }
    @Test void schemaTwoWithoutActivityReturnsRealZeroWithoutCreatingState() throws Exception {
        fixture.rows=Collections.singletonList(progressRow(CompletionTarget.Criterion.REPETITIONS,5,null));
        var p=fixture.repository().observeDetail(fixture.patient,"doctor",fixture.session).orElseThrow().technicalProgress();
        assertEquals("AVAILABLE",p.availability().name());assertEquals(5L,p.target());
        assertEquals(0L,p.activeMilliseconds());assertEquals(0L,p.completedRepetitions());assertFalse(p.targetReached());
        assertEquals(1,fixture.statements);assertEquals(1,fixture.executions);
    }
    @Test void readsFrozenTimeAndRepetitionGoalsWithoutInferringStatusOrElapsedTime() throws Exception {
        var repo=fixture.repository();
        for(var criterion:CompletionTarget.Criterion.values()) {
            for(boolean reached:List.of(false,true)) {
                var credited=state(reached?5000:4999,reached?5:4);
                for(String status:List.of("ACTIVE","ABANDONED","CANCELLED","FAILED_TECHNICAL","COMPLETED")) {
                    if(status.equals("COMPLETED")&&!reached)continue;
                    var r=progressRow(criterion,5,credited);r[4]=status;
                    if(!status.equals("ACTIVE"))r[6]=fixture.start.plusSeconds(10);
                    fixture.rows=Collections.singletonList(r);
                    var detail=repo.observeDetail(fixture.patient,"doctor",fixture.session).orElseThrow();
                    assertEquals(status,detail.session().status());assertEquals(criterion,detail.technicalProgress().criterion());
                    assertEquals(credited.activeMilliseconds(),detail.technicalProgress().activeMilliseconds());
                    assertEquals(credited.completedRepetitions(),detail.technicalProgress().completedRepetitions());
                    assertEquals(reached,detail.technicalProgress().targetReached());
                }
            }
        }
    }
    @Test void acceptsTheExistingEncodersNumericAndTextualAnchors() throws Exception {
        for(boolean timestamps:List.of(false,true)) {
            var r=progressRow(CompletionTarget.Criterion.REPETITIONS,5,state(4200,5));
            r[18]=progressMapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,timestamps).writeValueAsString(state(4200,5));
            fixture.rows=Collections.singletonList(r);
            assertTrue(fixture.repository().observeDetail(fixture.patient,"doctor",fixture.session).orElseThrow().technicalProgress().targetReached());
        }
    }
    @Test void corruptProgressFailsClosedInsteadOfBecomingZeroOrLegacy() throws Exception {
        var valid=progressMapper.valueToTree(state(4200,5));
        var invalid=new ArrayList<String>(List.of("{","{}","null","[]",valid+" {}"));
        var missing=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("activeMilliseconds");invalid.add(missing.toString());
        for(var change:List.of(new Object[]{"completedRepetitions",-1L},new Object[]{"activeMilliseconds","4200"},
                new Object[]{"mode","UNKNOWN"},new Object[]{"mode","NEW"},new Object[]{"repetitionIndex",1})) {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)valid.deepCopy();node.set((String)change[0],progressMapper.valueToTree(change[1]));invalid.add(node.toString());
        }
        var anchor=(com.fasterxml.jackson.databind.node.ObjectNode)valid.deepCopy();anchor.putNull("anchor");invalid.add(anchor.toString());
        invalid.add(valid.toString().replace("\"activeMilliseconds\":4200","\"activeMilliseconds\":4200,\"activeMilliseconds\":4200"));
        var service=new DoctorTherapySessionQueryService(fixture.repository());
        for(String json:invalid) {
            var r=progressRow(CompletionTarget.Criterion.REPETITIONS,5,state(4200,5));r[18]=json;fixture.rows=Collections.singletonList(r);
            assertThrows(IllegalStateException.class,()->service.detail("doctor",fixture.patient,fixture.session),json);
        }
    }
    @Test void mismatchedRowsAndInconsistentCompletedOrCreditedDataAreUnavailable() throws Exception {
        var service=new DoctorTherapySessionQueryService(fixture.repository());
        var wrongId=progressRow(CompletionTarget.Criterion.REPETITIONS,5,state(4200,5));wrongId[17]=new UUID(0,99);
        var missingState=progressRow(CompletionTarget.Criterion.REPETITIONS,5,state(4200,5));missingState[18]=null;
        var lostRow=progressRow(CompletionTarget.Criterion.REPETITIONS,5,null);lostRow[7]=1L;
        var completed=progressRow(CompletionTarget.Criterion.REPETITIONS,5,null);completed[4]="COMPLETED";
        var duration=progressRow(CompletionTarget.Criterion.REPETITIONS,5,state(4200,5));duration[7]=3L;
        var legacy=row(parameters().getFirst());legacy[17]=fixture.session;legacy[18]=progressMapper.writeValueAsString(SessionActivityProgress.empty());
        for(var r:List.of(wrongId,missingState,lostRow,completed,duration,legacy)) {
            fixture.rows=Collections.singletonList(r);
            assertThrows(IllegalStateException.class,()->service.detail("doctor",fixture.patient,fixture.session));
        }
    }
}
