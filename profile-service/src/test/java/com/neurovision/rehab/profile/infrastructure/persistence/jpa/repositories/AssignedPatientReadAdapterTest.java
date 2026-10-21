package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

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

        var adapter=new JpaPatientNameReadAdapter(em);
        assertTrue(adapter.names(Set.of("patient")).isEmpty());
        assertEquals(1,queries.size());
        assertEquals("select p.cognitoSubject,p.profileType,p.status,p.firstName,p.lastName from UserProfileEntity p where p.cognitoSubject in :subjects",queries.getFirst());
        assertTrue(parameters.contains(Map.entry("subjects",Set.of("patient"))));assertTrue(limits.contains(100));
        assertThrows(IllegalArgumentException.class,()->adapter.names(Set.of()));
    }
}
