package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapyPlanQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapyPlanReadRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import io.quarkus.security.identity.SecurityIdentity;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapyPlanReadControllerTest {
    final UUID patient=new UUID(0,1), planId=new UUID(0,2);
    final Instant now=Instant.parse("2026-10-21T12:00:00Z");
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    boolean assigned=true, missing=false, failed=false;
    int reads;
    String owner="doctor";
    TherapyPlan plan() {
        return TherapyPlan.rehydrate(new TherapyPlanId(planId),new ClinicalPatientId(patient),new CognitoSubject(owner),
                TherapyPlanStatus.ACTIVE,3,2,20,null,1,15,10,Map.of(new ExerciseCode("fixation"),1));
    }
    final DoctorTherapyPlanReadRepository repo=new DoctorTherapyPlanReadRepository() {
        public boolean hasActiveAssignment(UUID id,String doctor) {
            reads++; if(failed)throw new IllegalArgumentException("private storage detail");
            return assigned && id.equals(patient) && doctor.equals("doctor");
        }
        public Page page(UUID id,String doctor,TherapyPlanStatus status,Instant at,int offset,int limit) {
            reads++; return new Page(missing || offset>0 ? List.of():List.of(plan()),missing?0:1);
        }
        public Optional<Detail> detail(UUID id,UUID requested,String doctor,Instant at) {
            reads++;
            var parameters=new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.CENTER,500,1000,70,5,500,1);
            var config=TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(0,3)),
                    new TherapyPlanId(planId),TherapeuticExercise.FIXATION,2,1,now.minusSeconds(1),
                    new CognitoSubject("doctor"),now.minusSeconds(2),parameters);
            return missing || !requested.equals(planId)?Optional.empty():Optional.of(new Detail(plan(),List.of(config)));
        }
    };
    DoctorTherapyPlanReadController controller(SecurityIdentity identity) {
        return new DoctorTherapyPlanReadController(new DoctorTherapyPlanQueryService(repo,Clock.fixed(now,ZoneOffset.UTC)),identity,mapper);
    }
    SecurityIdentity identity(Set<String> roles,boolean anonymous) {
        return new AssignedPatientRequestSupportTest().identity(anonymous,roles,"doctor");
    }
    DoctorTherapyPlanReadController doctor(){return controller(identity(Set.of("DOCTOR"),false));}
    @Test void listHasBoundedPublicShapeAndEmptyPages() throws Exception {
        var response=doctor().list(patient.toString(),"0","20",null);
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeaderString("Cache-Control"));
        var json=mapper.valueToTree(response.getEntity());
        assertEquals(Set.of("clinicalPatientId","plans","page","size","total"),fields(json));
        assertEquals(Set.of("therapyPlanId","status","configurationVersion"),fields(json.path("plans").get(0)));
        assertEquals(1,json.path("total").asInt());
        var next=mapper.valueToTree(doctor().list(patient.toString(),"1","20",null).getEntity());
        assertTrue(next.path("plans").isEmpty());assertEquals(1,next.path("total").asInt());
        missing=true;
        assertEquals(200,doctor().list(patient.toString(),"0","20",null).getStatus());
    }
    @Test void detailContainsTypedParametersNotSubjectsOrHistory() {
        var response=doctor().detail(patient.toString(),planId.toString());
        assertEquals(200,response.getStatus());
        var json=mapper.valueToTree(response.getEntity());
        assertEquals(3,json.path("configurationVersion").asInt());assertTrue(json.path("dailyHardSafetyLimitMinutes").isNull());
        assertEquals(1,json.path("configurations").size());
        var config=json.path("configurations").get(0);
        assertEquals(Set.of("configurationId","therapyPlanId","exerciseCode","version","parameterSchemaVersion","effectiveFrom","status","parameters"),fields(config));
        assertEquals("fixation",config.path("exerciseCode").asText());
        assertEquals(500,config.path("parameters").path("minimumFixationMs").asInt());
        String raw=json.toString();assertFalse(raw.contains("doctor"));assertFalse(raw.contains("Subject"));assertFalse(raw.contains("createdAt"));
    }
    @Test void unknownWrongPatientAndForeignPlanShareUniformDenial() {
        var denied=doctor().detail(patient.toString(),new UUID(0,4).toString());
        var unknown=doctor().detail(new UUID(0,5).toString(),planId.toString());
        owner="other";var foreign=doctor().detail(patient.toString(),planId.toString());
        assigned=false;var unassigned=doctor().list(patient.toString(),"0","20",null);
        for(var result:List.of(denied,unknown,foreign,unassigned)) {
            assertEquals(403,result.getStatus());assertEquals(denied.getEntity(),result.getEntity());
            assertEquals("no-store",result.getHeaderString("Cache-Control"));
        }
    }
    @Test void authenticationAndRoleRejectionsDoNotRead() {
        for(var roles:List.of(Set.of("PATIENT"),Set.of("DOCTOR","PATIENT"),Set.<String>of()))
            assertEquals(403,controller(identity(roles,false)).list(patient.toString(),"0","20",null).getStatus());
        assertEquals(401,controller(identity(Set.of(),true)).detail(patient.toString(),planId.toString()).getStatus());
        SecurityIdentity noCredential=(SecurityIdentity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SecurityIdentity.class},
                (p,m,a)->m.getName().equals("getCredential")?null:m.invoke(identity(Set.of("DOCTOR"),false),a));
        assertEquals(401,controller(noCredential).list(patient.toString(),"0","20",null).getStatus());
        assertEquals(0,reads);
    }
    @Test void invalidInputIs400AndPersistenceDecodingIs503WithoutPrivateErrors() {
        for(String value:List.of("1","00000000-0000-0000-0000-00000000000A","invalid"))
            assertEquals(400,doctor().detail(value,planId.toString()).getStatus());
        for(String value:List.of("-1","1.0","x","2147483648",""))
            assertEquals(400,doctor().list(patient.toString(),value,"20",null).getStatus());
        assertEquals(400,doctor().list(patient.toString(),"0","101",null).getStatus());
        assertEquals(400,doctor().list(patient.toString(),"0","20","active").getStatus());
        failed=true;var result=doctor().detail(patient.toString(),planId.toString());
        assertEquals(503,result.getStatus());assertEquals(Map.of("code","PLAN_READ_UNAVAILABLE"),result.getEntity());
        assertEquals("no-store",result.getHeaderString("Cache-Control"));
    }
    @Test void getRoutesAreDoctorOnlyAndWritesRemainOnExistingController() throws Exception {
        for(String name:List.of("list","detail")) {
            var method=Arrays.stream(DoctorTherapyPlanReadController.class.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
            assertArrayEquals(new String[]{"DOCTOR"},method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        }
        assertTrue(Arrays.stream(DoctorTherapyPlanReadController.class.getMethods()).noneMatch(m->m.isAnnotationPresent(jakarta.ws.rs.POST.class)));
    }
    @Test void publicReadRecordsAreExplicitlyRegisteredForNativeSerialization() {
        var registration=DoctorTherapyPlanReadResponse.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class);
        assertNotNull(registration);
        assertEquals(Set.of(DoctorTherapyPlanReadResponse.Summary.class,DoctorTherapyPlanReadResponse.Page.class,
                DoctorTherapyPlanReadResponse.Configuration.class,DoctorTherapyPlanReadResponse.Detail.class),Set.of(registration.targets()));
    }
    static Set<String> fields(com.fasterxml.jackson.databind.JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
 @Test void signedOffsetExactBoundaryAndOverflowAreDistinguished() {
   assertEquals(200,doctor().list(patient.toString(),"2147483647","1",null).getStatus());
   assertEquals(400,doctor().list(patient.toString(),"1073741824","2",null).getStatus());
   assertEquals(200,doctor().list(patient.toString(),"21474836","100",null).getStatus());
   assertEquals(400,doctor().list(patient.toString(),"21474837","100",null).getStatus());
 }
 @Test void reassigningPatientImmediatelyRemovesBothReadPaths() {
   assertEquals(200,doctor().detail(patient.toString(),planId.toString()).getStatus());
   assigned=false;
   assertEquals(403,doctor().detail(patient.toString(),planId.toString()).getStatus());
   assertEquals(403,doctor().list(patient.toString(),"0","20",null).getStatus());
 }
 @Test void listAndDetailDecodeErrorsAreUnavailableNotClientErrors() {
   DoctorTherapyPlanReadRepository broken=new DoctorTherapyPlanReadRepository() {
    public boolean hasActiveAssignment(UUID p,String d){return true;}
    public Page page(UUID p,String d,TherapyPlanStatus st,Instant at,int off,int size){throw new IllegalArgumentException("private persisted value");}
    public Optional<Detail> detail(UUID p,UUID id,String d,Instant at){throw new IllegalArgumentException("private persisted value");}
   };
   var c=new DoctorTherapyPlanReadController(new DoctorTherapyPlanQueryService(broken,Clock.fixed(now,ZoneOffset.UTC)),identity(Set.of("DOCTOR"),false),mapper);
   for(var r:List.of(c.list(patient.toString(),"0","20",null),c.detail(patient.toString(),planId.toString()))) {
    assertEquals(503,r.getStatus());assertEquals(Map.of("code","PLAN_READ_UNAVAILABLE"),r.getEntity());assertEquals("no-store",r.getHeaderString("Cache-Control"));
   }
 }
 @Test void detailAndListEachReadClockExactlyOnce() {
   int[] ticks={0};
   var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;} public Instant instant(){ticks[0]++;return now;}};
   var c=new DoctorTherapyPlanReadController(new DoctorTherapyPlanQueryService(repo,clock),identity(Set.of("DOCTOR"),false),mapper);
   assertEquals(200,c.detail(patient.toString(),planId.toString()).getStatus());assertEquals(1,ticks[0]);
   assertEquals(200,c.list(patient.toString(),"0","20",null).getStatus());assertEquals(2,ticks[0]);
 }
 @Test void exactStatusDomainRejectsWhitespaceAndCase() {
   for(String status:List.of("ACTIVE","SUSPENDED","CANCELLED"))assertEquals(200,doctor().list(patient.toString(),"0","20",status).getStatus());
   for(String status:List.of("active","ACTIVE "," ACTIVE","","SUSPENDED,CANCELLED"))assertEquals(400,doctor().list(patient.toString(),"0","20",status).getStatus());
 }
}
