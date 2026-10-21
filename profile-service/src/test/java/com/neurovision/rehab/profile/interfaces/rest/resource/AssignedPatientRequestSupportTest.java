package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientRequestSupportTest {
    final AssignedPatientRequestSupport support=new AssignedPatientRequestSupport(new ObjectMapper());
    SecurityIdentity identity(boolean anonymous, Set<String> roles,String subject) {
        var token=(JsonWebToken)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{JsonWebToken.class},(p,m,a)->m.getName().equals("getSubject")?subject:null);
        return (SecurityIdentity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SecurityIdentity.class},(p,m,a)->switch(m.getName()) {
            case "isAnonymous" -> anonymous;
            case "hasRole" -> roles.contains(a[0]);
            case "getPrincipal" -> token;
            case "getCredential" -> new AccessTokenCredential("original");
            default -> null;
        });
    }
    @Test void authenticatedDoctorComesOnlyFromIdentity() {
        var actor=support.actor(identity(false,Set.of("DOCTOR"),"doctor"));
        assertEquals("doctor",actor.subject());assertEquals("original",actor.token());
        assertFalse(actor.toString().contains("original"));
    }
    @Test void deniedIdentitiesNeverInvokeAction() {
        for(var i:List.of(identity(true,Set.of(),null),identity(false,Set.of("PATIENT"),"patient"),identity(false,Set.of("DOCTOR","PATIENT"),"doctor"),identity(false,Set.of("DOCTOR")," "))) {
            int[] calls={0};
            var response=support.response(()->{support.actor(i);calls[0]++;return Map.of();});
            assertTrue(response.getStatus()==401 || response.getStatus()==403);assertEquals(0,calls[0]);
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
        }
    }
    @Test void claimedBindingsMustBeClosedAndMatchEveryRequestedId() {
        String id=new UUID(0,1).toString();
        String valid="{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"],\"patientSubjects\":{\""+id+"\":\"synthetic-subject\"}}";
        assertEquals(Map.of(new UUID(0,1),"synthetic-subject"),support.targets(valid));
        for(String invalid:List.of(valid.replace("synthetic-subject"," "),valid.replace("\"patientSubjects\":","\"extra\":true,\"patientSubjects\":"),valid.replace("\":\"synthetic-subject\"","x\":\"synthetic-subject\""),valid+"{}")) {
            assertThrows(RuntimeException.class,()->support.targets(invalid));
        }
    }
    @Test void acceptsOnlyClosedBoundedCanonicalRequest() {
        String id=new UUID(0,1).toString();String valid="{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"]}";
        assertEquals(List.of(new UUID(0,1)),support.ids(valid));
        for(String invalid:List.of(valid+"{}",valid.replace("1,","2,"),valid.replace("[\""+id+"\"]","[]"),valid.replace("[\""+id+"\"]","[\""+id+"\",\""+id+"\"]"),valid.replace("\"contractVersion\":1","\"doctorSubject\":\"spoof\",\"contractVersion\":1"),"{}","x".repeat(17000))) {
            assertThrows(RuntimeException.class,()->support.ids(invalid));
        }
    }
}
