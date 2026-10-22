package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapyPlanQueryServiceTest {
    static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    static TherapyPlan plan(String doctor, UUID patient) {
        return TherapyPlan.rehydrate(new TherapyPlanId(PLAN), new ClinicalPatientId(patient),
                new CognitoSubject(doctor), TherapyPlanStatus.ACTIVE, 1, 2, 20, null, 1, 15, 10,
                Map.of(new ExerciseCode("fixation"), 1));
    }
    static class Fake implements DoctorTherapyPlanReadRepository {
        boolean assigned = true;
        int reads;
        TherapyPlan value = plan("doctor", PATIENT);
        Instant at;
        TherapyPlanStatus status;
        int offset, limit;
        List<TherapeuticExerciseConfiguration> configurations = List.of();
        boolean missing;
        public boolean hasActiveAssignment(UUID patient, String doctor) { return assigned; }
        public Page page(UUID patient, String doctor, TherapyPlanStatus status, Instant at, int offset, int limit) {
            reads++; this.at=at; this.status=status; this.offset=offset; this.limit=limit;
            return new Page(offset == 0 ? List.of(value) : List.of(), 1);
        }
        public Optional<Detail> detail(UUID patient, UUID plan, String doctor, Instant at) {
            reads++; this.at=at; return missing ? Optional.empty() : Optional.of(new Detail(value, configurations));
        }
    }
    DoctorTherapyPlanQueryService service(Fake fake) {
        return new DoctorTherapyPlanQueryService(fake, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @Test void pagesWithCapturedInstantAndExactFilter() {
        var fake=new Fake(); var result=service(fake).list("doctor",PATIENT,2,20,"SUSPENDED");
        assertEquals(40,fake.offset); assertEquals(20,fake.limit); assertEquals(NOW,fake.at);
        assertEquals(TherapyPlanStatus.SUSPENDED,fake.status);
        assertEquals(1,result.total()); assertTrue(result.plans().isEmpty());
        assertEquals(2,result.page()); assertEquals(20,result.size());
    }
    @Test void defaultsAllStatesAndAllowsMaximumPage() {
        var fake=new Fake(); var result=service(fake).list("doctor",PATIENT,0,100,null);
        assertNull(fake.status); assertEquals(1,result.plans().size()); assertEquals(100,fake.limit);
        assertEquals(PATIENT,result.clinicalPatientId());
    }
    @Test void deniesUnassignedPatientBeforeAnyPlanRead() {
        var fake=new Fake(); fake.assigned=false;
        assertThrows(SecurityException.class,()->service(fake).list("doctor",PATIENT,0,20,null));
        assertThrows(SecurityException.class,()->service(fake).detail("doctor",PATIENT,PLAN));
        assertEquals(0,fake.reads);
    }
    @Test void deniesForeignOwnerAndWrongPatientEvenWithAssignment() {
        var fake=new Fake(); fake.value=plan("other",PATIENT);
        assertThrows(SecurityException.class,()->service(fake).detail("doctor",PATIENT,PLAN));
        assertThrows(SecurityException.class,()->service(fake).list("doctor",PATIENT,0,20,null));
        fake.value=plan("doctor",UUID.randomUUID());
        assertThrows(SecurityException.class,()->service(fake).detail("doctor",PATIENT,PLAN));
    }
    @Test void rejectsInvalidBoundsAndStatusBeforePersistence() {
        var fake=new Fake(); var service=service(fake);
        for (int[] bounds:List.of(new int[]{-1,20},new int[]{0,0},new int[]{0,101},new int[]{Integer.MAX_VALUE,2}))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",PATIENT,bounds[0],bounds[1],null));
        for(String status:List.of("active","","UNKNOWN"," ACTIVE"))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",PATIENT,0,20,status));
        assertEquals(0,fake.reads);
    }
    @Test void detailKeepsEffectivePlanAndEmptyConfigurations() {
        var fake=new Fake(); var result=service(fake).detail("doctor",PATIENT,PLAN);
        assertSame(fake.value,result.plan()); assertTrue(result.configurations().isEmpty()); assertEquals(NOW,fake.at);
    }
    @Test void unknownAndWrongPlanAreDenied() {
        var fake=new Fake(); fake.missing=true;
        assertThrows(SecurityException.class,()->service(fake).detail("doctor",PATIENT,PLAN));
        fake.missing=false;
        assertThrows(SecurityException.class,()->service(fake).detail("doctor",PATIENT,UUID.randomUUID()));
    }
    @Test void futureForeignInactiveAndDuplicateConfigurationsFailClosed() {
        var fake=new Fake();
        var valid=config(PLAN,NOW.minusSeconds(1),TherapeuticConfigurationStatus.ACTIVE);
        fake.configurations=List.of(valid);
        assertEquals(1,service(fake).detail("doctor",PATIENT,PLAN).configurations().size());
        for(var configs:List.of(List.of(config(PLAN,NOW.plusSeconds(1),TherapeuticConfigurationStatus.ACTIVE)),
                List.of(config(UUID.randomUUID(),NOW.minusSeconds(1),TherapeuticConfigurationStatus.ACTIVE)),
                List.of(config(PLAN,NOW.minusSeconds(1),TherapeuticConfigurationStatus.REVOKED)),List.of(valid,valid))) {
            fake.configurations=configs;
            assertThrows(IllegalStateException.class,()->service(fake).detail("doctor",PATIENT,PLAN));
        }
    }
    static TherapeuticExerciseConfiguration config(UUID plan,Instant effective,TherapeuticConfigurationStatus status) {
        return TherapeuticExerciseConfiguration.rehydrate(new TherapeuticConfigurationId(UUID.randomUUID()),
                new TherapyPlanId(plan),TherapeuticExercise.FIXATION,1,1,effective,new CognitoSubject("doctor"),
                NOW.minusSeconds(10),status,new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.CENTER,500,1000,70,5,500,1));
    }
}
