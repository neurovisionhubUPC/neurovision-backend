package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapySessionReadControllerTest {
    final UUID patient=new UUID(0,1);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    boolean assigned=true,broken;
    int calls;
    SecurityIdentity identity(Set<String> roles,boolean anonymous) {
        return new AssignedPatientRequestSupportTest().identity(anonymous,roles,"doctor");
    }
    DoctorTherapySessionReadController controller(SecurityIdentity identity) {
        return new DoctorTherapySessionReadController(new DoctorTherapySessionQueryService((p,d,f,o,l)->{
            calls++;
            if(broken) throw new IllegalArgumentException("private storage details");
            if(!assigned || !p.equals(patient)) return new Observation(false,List.of(),0);
            var row=new Session(new UUID(0,2),new UUID(0,3),"fixation","ACTIVE",
                    Instant.parse("2020-01-01T00:00:00Z"),null,17,LocalDate.of(2020,1,1),new UUID(0,4),2,1);
            return new Observation(true,o==0?List.of(row):List.of(),1);
        }),identity,mapper);
    }
    DoctorTherapySessionReadController doctor(){return controller(identity(Set.of("DOCTOR"),false));}
    Response list(DoctorTherapySessionReadController c){return c.list(patient.toString(),"0","20",null,null,null,null);}
    Set<String> fields(com.fasterxml.jackson.databind.JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    @Test void whitelistsPublicShapeAndKeepsNullableEndAndPersistedDuration() {
        var response=list(doctor()); assertEquals(200,response.getStatus());
        assertEquals("no-store",response.getHeaderString("Cache-Control"));
        var json=mapper.valueToTree(response.getEntity());
        assertEquals(Set.of("clinicalPatientId","sessions","page","size","total"),fields(json));
        var row=json.path("sessions").get(0);
        assertEquals(Set.of("therapySessionId","therapyPlanId","exerciseCode","status","startedAt","endedAt",
                "activeDurationSeconds","quotaDate","configurationId","configurationVersion","parameterSchemaVersion"),fields(row));
        assertTrue(row.path("endedAt").isNull()); assertEquals(17,row.path("activeDurationSeconds").asLong());
        assertEquals("2020-01-01T00:00:00Z",row.path("startedAt").asText());
        assertEquals("2020-01-01",row.path("quotaDate").asText());
        assertEquals("ACTIVE",row.path("status").asText()); assertEquals(2,row.path("configurationVersion").asLong());
        var next=mapper.valueToTree(doctor().list(patient.toString(),"1","20",null,null,null,null).getEntity());
        assertTrue(next.path("sessions").isEmpty()); assertEquals(1,next.path("total").asLong());
    }
    @Test void rejectsWrongMixedRolesAnonymousAndMissingCredentialBeforeReading() {
        for(var roles:List.of(Set.of("PATIENT"),Set.of("ADMIN"),Set.of("DOCTOR","PATIENT"),Set.of("DOCTOR","ADMIN"),Set.<String>of()))
            assertEquals(403,list(controller(identity(roles,false))).getStatus());
        assertEquals(401,list(controller(identity(Set.of(),true))).getStatus());
        var original=identity(Set.of("DOCTOR"),false);
        var absent=(SecurityIdentity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SecurityIdentity.class},
                (p,m,a)->m.getName().equals("getCredential")?null:m.invoke(original,a));
        assertEquals(401,list(controller(absent)).getStatus()); assertEquals(0,calls);
    }
    @Test void uniformUnknownAndRevokedPatientDenialAndSafeUnavailable() {
        var unknown=doctor().list(new UUID(0,99).toString(),"0","20",null,null,null,null);
        assigned=false; var revoked=list(doctor());
        assertEquals(403,unknown.getStatus()); assertEquals(403,revoked.getStatus());
        assertEquals(unknown.getEntity(),revoked.getEntity()); assertEquals("no-store",revoked.getHeaderString("Cache-Control"));
        broken=true; var failed=list(doctor());
        assertEquals(503,failed.getStatus()); assertEquals(Map.of("code","SESSION_READ_UNAVAILABLE"),failed.getEntity());
        assertEquals("no-store",failed.getHeaderString("Cache-Control"));
    }
    @Test void invalidUuidPaginationAndFiltersAreSanitized400WithoutReading() {
        for(String id:List.of("bad","1-1-1-1-1","00000000-0000-0000-0000-00000000000A"))
            assertEquals(400,doctor().list(id,"0","20",null,null,null,null).getStatus());
        for(String page:List.of("-1","x","","2147483648","1.0"))
            assertEquals(400,doctor().list(patient.toString(),page,"20",null,null,null,null).getStatus());
        for(String size:List.of("0","101","-1"))
            assertEquals(400,doctor().list(patient.toString(),"0",size,null,null,null,null).getStatus());
        assertEquals(400,doctor().list(patient.toString(),"2147483647","2",null,null,null,null).getStatus());
        assertEquals(400,doctor().list(patient.toString(),"0","20","FIXATION",null,null,null).getStatus());
        assertEquals(400,doctor().list(patient.toString(),"0","20",null,"active",null,null).getStatus());
        var badDate=doctor().list(patient.toString(),"0","20",null,null,"2026-10-01T12:00:00",null);
        assertEquals(400,badDate.getStatus()); assertEquals("no-store",badDate.getHeaderString("Cache-Control")); assertEquals(0,calls);
    }
    @Test void routeIsReadOnlyDoctorOnlyWithDefaultsAndNativeReflection() throws Exception {
        var method=DoctorTherapySessionReadController.class.getMethod("list",String.class,String.class,String.class,String.class,String.class,String.class,String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
        assertArrayEquals(new String[]{"DOCTOR"},method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertEquals("0",method.getParameters()[1].getAnnotation(jakarta.ws.rs.DefaultValue.class).value());
        assertEquals("20",method.getParameters()[2].getAnnotation(jakarta.ws.rs.DefaultValue.class).value());
        assertEquals(Set.of(DoctorTherapySessionReadResponse.Session.class,DoctorTherapySessionReadResponse.Page.class),
                Set.of(DoctorTherapySessionReadResponse.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class).targets()));
        assertEquals("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions",
                DoctorTherapySessionReadController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
    }
}
