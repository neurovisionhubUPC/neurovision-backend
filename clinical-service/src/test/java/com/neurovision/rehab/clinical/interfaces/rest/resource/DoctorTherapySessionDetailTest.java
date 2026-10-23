package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import io.quarkus.security.identity.SecurityIdentity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapySessionDetailTest {
    final UUID patient=new UUID(0,1),session=new UUID(0,2),plan=new UUID(0,3),configuration=new UUID(0,4);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    int calls;
    boolean assigned=true,broken;
    final Fixation parameters=new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.CENTER,500,2000,50,10,1000,1);
    final Session summary=new Session(session,plan,"fixation","ACTIVE",Instant.parse("2020-01-01T00:00:00Z"),null,17,LocalDate.of(2020,1,1),configuration,2,1);
    DoctorTherapySessionReadController controller(SecurityIdentity identity) {
        var port=new DoctorTherapySessionReadRepository() {
            public Observation observe(UUID p,String d,Filters f,int o,int l){throw new AssertionError("List is not the detail authorization path");}
            public Optional<Detail> observeDetail(UUID p,String d,UUID id) {
                calls++;
                if(broken)throw new IllegalArgumentException("private persisted JSON");
                if(!assigned || !patient.equals(p) || !session.equals(id) || !"doctor".equals(d))return Optional.empty();
                return Optional.of(new Detail(patient,summary,parameters,new SessionLimits(1,10,25)));
            }
        };
        return new DoctorTherapySessionReadController(new DoctorTherapySessionQueryService(port),identity,mapper);
    }
    SecurityIdentity identity(Set<String> roles,boolean anonymous){return new AssignedPatientRequestSupportTest().identity(anonymous,roles,"doctor");}
    DoctorTherapySessionReadController doctor(){return controller(identity(Set.of("DOCTOR"),false));}
    @Test void returnsOnlyFrozenDetailWithoutInferringExpiryOrCompletion() {
        var response=doctor().detail(patient.toString(),session.toString());
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeaderString("Cache-Control"));
        var json=mapper.valueToTree(response.getEntity());var names=new HashSet<String>();json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("clinicalPatientId","therapySessionId","therapyPlanId","exerciseCode","status","startedAt","endedAt","activeDurationSeconds","quotaDate","configurationId","configurationVersion","parameterSchemaVersion","configurationParameters","sessionLimits"),names);
        assertEquals("2020-01-01T00:00:00Z",json.path("startedAt").asText());assertTrue(json.path("endedAt").isNull());
        assertEquals("2020-01-01",json.path("quotaDate").asText());assertEquals("ACTIVE",json.path("status").asText());
        assertEquals(17,json.path("activeDurationSeconds").asLong());assertEquals(2,json.path("configurationVersion").asLong());
        assertEquals(mapper.valueToTree(parameters),json.path("configurationParameters"));
        var limits=json.path("sessionLimits");var limitNames=new HashSet<String>();limits.fieldNames().forEachRemaining(limitNames::add);
        assertEquals(Set.of("minimumValidDurationMinutes","maximumSessionDurationMinutes","minimumValidSampleCount"),limitNames);
        assertEquals(1,limits.path("minimumValidDurationMinutes").asInt());assertEquals(10,limits.path("maximumSessionDurationMinutes").asInt());assertEquals(25L,limits.path("minimumValidSampleCount").asLong());
        assertEquals(1,calls);
    }
    @Test void deniesWrongRolesAndAnonymousBeforeAnyObservation() {
        for(var roles:List.of(Set.of("PATIENT"),Set.of("ADMIN"),Set.of("DOCTOR","PATIENT"),Set.of("DOCTOR","ADMIN")))
            assertEquals(403,controller(identity(roles,false)).detail(patient.toString(),session.toString()).getStatus());
        assertEquals(401,controller(identity(Set.of(),true)).detail(patient.toString(),session.toString()).getStatus());assertEquals(0,calls);
    }
    @Test void deniesMissingForeignMismatchedAndRevokedWithSameSafeResponse() {
        var unknown=doctor().detail(patient.toString(),new UUID(0,99).toString());
        var mismatch=doctor().detail(new UUID(0,99).toString(),session.toString());assigned=false;
        var revoked=doctor().detail(patient.toString(),session.toString());
        assigned=true;var foreign=controller(new AssignedPatientRequestSupportTest().identity(false,Set.of("DOCTOR"),"other-doctor")).detail(patient.toString(),session.toString());
        for(var response:List.of(unknown,mismatch,revoked,foreign)){
            assertEquals(403,response.getStatus());assertEquals(Map.of("code","SESSION_READ_FORBIDDEN"),response.getEntity());assertEquals("no-store",response.getHeaderString("Cache-Control"));
        }
    }
    @Test void badPathIs400ButPersistedDecodeErrorIs503() {
        for(String invalid:List.of("bad","1-1-1-1-1","00000000-0000-0000-0000-00000000000A")){
            assertEquals(400,doctor().detail(invalid,session.toString()).getStatus());assertEquals(400,doctor().detail(patient.toString(),invalid).getStatus());
        }
        assertEquals(0,calls);broken=true;var response=doctor().detail(patient.toString(),session.toString());
        assertEquals(503,response.getStatus());assertEquals(Map.of("code","SESSION_READ_UNAVAILABLE"),response.getEntity());assertEquals("no-store",response.getHeaderString("Cache-Control"));
    }
    @Test void detailRouteAndNativeRecordsAreExplicit() throws Exception {
        var method=DoctorTherapySessionReadController.class.getMethod("detail",String.class,String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));assertEquals("/{therapySessionId}",method.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertArrayEquals(new String[]{"DOCTOR"},method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        for(var type:List.of(DoctorTherapySessionReadResponse.Detail.class,DoctorTherapySessionReadResponse.SessionLimits.class))
            assertNotNull(type.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class));
    }
}
