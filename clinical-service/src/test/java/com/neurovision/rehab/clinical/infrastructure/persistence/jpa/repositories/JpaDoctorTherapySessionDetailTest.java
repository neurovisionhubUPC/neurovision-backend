package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
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
                17L,LocalDate.of(2026,10,22),fixture.configuration,2L,1,p.exercise().name(),codec.encode(p),1,10,25L};
    }
    @Test void oneReadRestrictsPatientSessionOwnerAndActiveAssignmentWithoutCurrentPlan() {
        fixture.rows=List.of();var repo=fixture.repository();
        assertTrue(repo.observeDetail(fixture.patient,"doctor",fixture.session).isEmpty());
        assertEquals(1,fixture.statements);assertEquals(1,fixture.executions);
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
}
