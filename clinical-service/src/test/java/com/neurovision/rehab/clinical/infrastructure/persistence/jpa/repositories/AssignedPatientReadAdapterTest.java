package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientReadAdapterTest {
    @Test void usesOnlyParameterizedBoundedScopedReads() {
        var queries=new ArrayList<String>();var parameters=new ArrayList<Map.Entry<Object,Object>>();
        var limits=new ArrayList<Integer>();var offsets=new ArrayList<Integer>();var id=new UUID(0,1);
        var q=(TypedQuery<?>)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{TypedQuery.class},(p,m,a)->switch(m.getName()) {
            case "setParameter" -> {parameters.add(Map.entry(a[0],a[1]));yield p;}
            case "setMaxResults" -> {limits.add((Integer)a[0]);yield p;}
            case "setFirstResult" -> {offsets.add((Integer)a[0]);yield p;}
            case "getResultList" -> List.of();
            case "getSingleResult" -> 0L;
            default -> throw new AssertionError("Unexpected query operation: "+m.getName());
        });
        var em=(EntityManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EntityManager.class},(p,m,a)->{
            assertEquals("createQuery",m.getName());queries.add((String)a[0]);return q;
        });

        var adapter=new JpaAssignedPatientReadAdapter(em);
        assertEquals(0,adapter.list("doctor",20,20).total());
        assertTrue(adapter.detail("doctor",id).isEmpty());
        assertTrue(adapter.resolve("doctor",List.of(id)).isEmpty());
        assertEquals(4,queries.size());
        for(String query:queries) {
            assertTrue(query.contains("exists (select a.id from DoctorPatientAssignmentEntity"));
            assertTrue(query.contains("a.doctorSubject=:doctor"));assertTrue(query.contains("a.status='ACTIVE'"));
        }
        assertTrue(queries.get(1).contains("order by p.createdAt desc,p.id asc"));
        assertTrue(parameters.contains(Map.entry("doctor","doctor")));
        assertTrue(parameters.contains(Map.entry("id",id)));
        assertTrue(parameters.contains(Map.entry("ids",List.of(id))));
        assertTrue(limits.contains(20));assertTrue(offsets.contains(20));
        assertTrue(queries.get(3).contains("p.id in :ids"));
        assertTrue(queries.get(2).contains("p.id=:id"));
    }
}
