package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapyPlanEntity;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JpaDoctorTherapyPlanReadRepositoryTest {
    final UUID patient=new UUID(0,1), planId=new UUID(0,2);
    final Instant now=Instant.parse("2026-10-21T12:00:00Z");
    final List<Capture> captures=new ArrayList<>();
    final List<TherapyPlanRevision> changes=new ArrayList<>();
    final List<TherapeuticExercise> exercises=new ArrayList<>();
    boolean empty=false;
    TherapyPlan base(){return TherapyPlan.rehydrate(new TherapyPlanId(planId),new ClinicalPatientId(patient),new CognitoSubject("doctor"),
            TherapyPlanStatus.ACTIVE,4,2,20,null,1,15,10,Map.of(new ExerciseCode("fixation"),1));}
    class Capture {
        String sql;Map<String,Object> params=new HashMap<>();int offset=-1,limit=-1;
        Capture(String sql){this.sql=sql;}
        Query query(){return (Query)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Query.class},(p,m,a)->{
            return switch(m.getName()) {
                case "setParameter" -> {params.put((String)a[0],a[1]);yield p;}
                case "setFirstResult" -> {offset=(int)a[0];yield p;}
                case "setMaxResults" -> {limit=(int)a[0];yield p;}
                case "getSingleResult" -> sql.startsWith("SELECT count")?5L:Boolean.TRUE;
                case "getResultList" -> empty?List.of():List.of(TherapyPlanEntity.fromDomain(base()));
                default -> throw new AssertionError("Unexpected persistence operation: "+m.getName());
            };
        });}
    }
    @SuppressWarnings("unchecked") <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{type},handler);
    }
    JpaDoctorTherapyPlanReadRepository repository(){
        EntityManager em=proxy(EntityManager.class,(p,m,a)->{
            assertEquals("createNativeQuery",m.getName());var c=new Capture((String)a[0]);captures.add(c);
            if(a.length==2)assertEquals(TherapyPlanEntity.class,a[1]);return c.query();
        });
        TherapyPlanRevisionRepository revisions=proxy(TherapyPlanRevisionRepository.class,(p,m,a)->{
            assertEquals("findEffective",m.getName());assertEquals(new TherapyPlanId(planId),a[0]);assertEquals(now,a[1]);return changes;
        });
        TherapeuticExerciseConfigurationRepository configs=proxy(TherapeuticExerciseConfigurationRepository.class,(p,m,a)->{
            assertEquals("findEffective",m.getName());assertEquals(new TherapyPlanId(planId),a[0]);assertEquals(now,a[2]);
            exercises.add((TherapeuticExercise)a[1]);return Optional.empty();
        });
        return new JpaDoctorTherapyPlanReadRepository(em,revisions,configs);
    }
    @Test void sameOwnerAssignmentAndEffectiveStatusPredicatePrecedePaginationAndCount(){
        var result=repository().page(patient,"doctor",TherapyPlanStatus.SUSPENDED,now,40,20);
        assertEquals(5,result.total());assertEquals(2,captures.size());
        var count=captures.get(0);var page=captures.get(1);
        assertEquals(count.sql.substring(count.sql.indexOf(" WHERE ")),page.sql.substring(page.sql.indexOf(" WHERE "),page.sql.lastIndexOf(" ORDER BY p.id ASC")));
        for(var c:captures){
            assertTrue(c.sql.contains("p.doctor_cognito_sub = :doctor"));assertTrue(c.sql.contains("a.status = 'ACTIVE'"));
            assertTrue(c.sql.contains("r.plan_status IS NOT NULL"));assertTrue(c.sql.contains("r.effective_from <= :at"));
            assertTrue(c.sql.contains("ORDER BY r.sequence DESC LIMIT 1"));
            assertEquals(Map.of("patient",patient,"doctor","doctor","at",now,"status","SUSPENDED"),c.params);
        }
        assertEquals(40,page.offset);assertEquals(20,page.limit);assertEquals(-1,count.limit);
    }
    @Test void nullStatusIncludesAllStatesAndAssignmentIsCheckedIndependently(){
        var repo=repository();assertTrue(repo.hasActiveAssignment(patient,"doctor"));
        repo.page(patient,"doctor",null,now,0,100);
        for(var c:captures){assertFalse(c.params.containsKey("status"));assertFalse(c.params.containsKey("at"));}
        assertFalse(captures.get(1).sql.contains("plan_status"));
    }
    @Test void resolvesPartialRevisionsBySequenceAndExcludesFutureStatus(){
        changes.add(revision(1,TherapyPlanStatus.SUSPENDED,null,now.minusSeconds(20)));
        changes.add(revision(2,null,7,now.minusSeconds(10)));
        changes.add(revision(3,TherapyPlanStatus.CANCELLED,null,now.plusSeconds(1)));
        var result=repository().detail(patient,planId,"doctor",now).orElseThrow();
        assertEquals(TherapyPlanStatus.SUSPENDED,result.plan().status());assertEquals(7,result.plan().dailySessionLimit());
        assertEquals(4,result.plan().configurationVersion());assertTrue(result.configurations().isEmpty());
        assertEquals(List.of(TherapeuticExercise.FIXATION),exercises);
        assertEquals(planId,captures.getFirst().params.get("plan"));assertEquals(1,captures.getFirst().limit);
    }
    @Test void unknownPlanDoesNotReadConfigurations(){empty=true;assertTrue(repository().detail(patient,planId,"doctor",now).isEmpty());assertTrue(exercises.isEmpty());}
    @Test void rejectsUnboundedPagesWithoutQueries(){
        var repo=repository();for(int limit:List.of(0,101))assertThrows(IllegalArgumentException.class,()->repo.page(patient,"doctor",null,now,0,limit));
        assertTrue(captures.isEmpty());
    }
    TherapyPlanRevision revision(long seq,TherapyPlanStatus status,Integer limit,Instant effective){
        return TherapyPlanRevision.create(new TherapyPlanRevisionId(UUID.randomUUID()),new TherapyPlanId(planId),seq,
                UUID.randomUUID(),"a".repeat(64),TherapyPlanRevisionKind.IMMEDIATE,effective,now.minusSeconds(60),
                new CognitoSubject("doctor"),status,limit,null,null,null,status==null?null:"Synthetic reason");
    }
}
