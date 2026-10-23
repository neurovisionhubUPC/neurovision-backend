package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JpaDoctorTherapySessionReadRepositoryTest {
    String sql;
    int statements,executions;
    final Map<String,Object> parameters=new HashMap<>();
    final UUID patient=new UUID(0,1),session=new UUID(0,2),plan=new UUID(0,3),configuration=new UUID(0,4);
    final Instant start=Instant.parse("2026-10-22T12:00:00Z");
    List<Object[]> rows=Collections.singletonList(new Object[]{true,0L,null});
    @SuppressWarnings("unchecked") <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler h) {
        return (T)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{type},h);
    }
    JpaDoctorTherapySessionReadRepository repository() {
        Query query=proxy(Query.class,(p,m,a)->switch(m.getName()) {
            case "setParameter" -> {parameters.put((String)a[0],a[1]); yield p;}
            case "getResultList" -> {executions++; yield rows;}
            default -> throw new AssertionError("Unexpected query operation: "+m.getName());
        });
        EntityManager em=proxy(EntityManager.class,(p,m,a)->{
            assertEquals("createNativeQuery",m.getName()); assertEquals(1,a.length);
            statements++; sql=(String)a[0]; return query;
        });
        return new JpaDoctorTherapySessionReadRepository(em,new com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }
    Filters none(){return new Filters(null,null,null,null);}
    Object[] row(Object timestamp,Object date,String status) {
        return new Object[]{true,1L,session,plan,"fixation",status,timestamp,null,17L,date,configuration,2L,1};
    }
    @Test void oneStatementScopesOwnerAssignmentFiltersTotalAndBoundedPage() {
        var f=new Filters("fixation",TherapySessionStatus.COMPLETED,start,start.plusSeconds(60));
        repository().observe(patient,"doctor",f,40,20);
        assertEquals(1,statements); assertEquals(1,executions);
        assertEquals(Map.of("patient",patient,"doctor","doctor","exercise","fixation","status","COMPLETED",
                "from",start,"to",start.plusSeconds(60),"offset",40,"limit",20),parameters);
        for(String part:List.of("a.status = 'ACTIVE'","s.clinical_patient_id = :patient","s.doctor_cognito_sub = :doctor",
                "s.exercise_code = :exercise","s.status = :status","s.started_at >= :from","s.started_at < :to",
                "SELECT count(*) FROM filtered","ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset",
                "LEFT JOIN page p ON true","ORDER BY p.started_at DESC, p.id DESC")) assertTrue(sql.contains(part),part);
        for(String forbidden:List.of("therapy_plans","configuration_parameters","idempotency_key","request_fingerprint",
                "termination_reason","FOR UPDATE","UPDATE ","DELETE ","INSERT ")) assertFalse(sql.contains(forbidden),forbidden);
    }
    @Test void emptyAndOutOfRangePagesRetainAuthorizationAndTotal() {
        var repo=repository();
        assertEquals(new Observation(true,List.of(),0),repo.observe(patient,"doctor",none(),0,20));
        rows=Collections.singletonList(new Object[]{true,7L,null});
        assertEquals(new Observation(true,List.of(),7),repo.observe(patient,"doctor",none(),100,20));
        rows=Collections.singletonList(new Object[]{false,0L,null});
        assertFalse(repo.observe(patient,"doctor",none(),0,20).authorized());
        assertEquals(Set.of("patient","doctor","offset","limit"),parameters.keySet());
        assertFalse(sql.contains("s.status = :status")); assertFalse(sql.contains("lease_expires_at"));
    }
    @Test void mapsFrozenReferencesAndAllPersistedStatesWithoutWallClockOrPayload() {
        var repo=repository();
        for(var state:TherapySessionStatus.values()) {
            rows=Collections.singletonList(row(start,LocalDate.of(2026,10,22),state.name()));
            var value=repo.observe(patient,"doctor",none(),0,20).sessions().getFirst();
            assertEquals(session,value.therapySessionId()); assertEquals(plan,value.therapyPlanId());
            assertEquals(configuration,value.configurationId()); assertEquals(2,value.configurationVersion());
            assertEquals(1,value.parameterSchemaVersion()); assertEquals(state.name(),value.status());
            assertEquals(17,value.activeDurationSeconds()); assertNull(value.endedAt()); assertEquals(start,value.startedAt());
        }
    }
    @Test void supportsDriverTemporalRepresentationsAndNullableEnd() {
        var repo=repository();
        for(Object time:List.of(start,start.atOffset(ZoneOffset.ofHours(-5)),java.sql.Timestamp.from(start))) {
            Object[] r=row(time,java.sql.Date.valueOf("2026-10-22"),"COMPLETED"); r[7]=time;
            rows=Collections.singletonList(r);
            var value=repo.observe(patient,"doctor",none(),0,20).sessions().getFirst();
            assertEquals(start,value.startedAt()); assertEquals(start,value.endedAt());
            assertEquals(LocalDate.of(2026,10,22),value.quotaDate());
        }
    }
    @Test void invalidPageDoesNotQueryAndMalformedObservationFailsClosed() {
        var repo=repository();
        for(int[] b:List.of(new int[]{-1,20},new int[]{0,0},new int[]{0,101}))
            assertThrows(IllegalArgumentException.class,()->repo.observe(patient,"doctor",none(),b[0],b[1]));
        assertEquals(0,statements);
        rows=List.of(); assertThrows(IllegalStateException.class,()->repo.observe(patient,"doctor",none(),0,20));
        rows=Collections.singletonList(row("private invalid timestamp",LocalDate.now(),"ACTIVE"));
        assertThrows(IllegalStateException.class,()->repo.observe(patient,"doctor",none(),0,20));
    }
}
