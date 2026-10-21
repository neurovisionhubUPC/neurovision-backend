package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AssignedPatientQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.AssignedPatientReadPort;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientControllerTest {
    final UUID id=new UUID(0,1);
    final ObjectMapper mapper=new ObjectMapper();
    int reads,names;
    AssignedPatientReadPort port=new AssignedPatientReadPort(){
        Patient row(){return new Patient(id,"PENDING_ASSESSMENT",Instant.EPOCH,Instant.EPOCH,null,List.of());}
        public Page list(String doctor,int offset,int size){assertEquals("doctor",doctor);reads++;return new Page(List.of(row()),1);}
        public Optional<Patient> detail(String doctor,UUID target){reads++;return id.equals(target)?Optional.of(row()):Optional.empty();}
        public Map<UUID,String> resolve(String doctor,List<UUID> ids){reads++;return Map.of(id,"private-subject");}
    };
    AssignedPatientQueryService service(){return new AssignedPatientQueryService(port,(ids,token)->{names++;return Map.of(id,new com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort.Name("PENDING_COMPLETION",null,null));});}
    @Test void listAndDetailExposeOnlyAuthorizedPublicFields() {
        var identity=new AssignedPatientRequestSupportTest().identity(false,Set.of("DOCTOR"),"doctor");
        var c=new DoctorClinicalPatientController(service(),identity,mapper);
        var listed=c.list(0,20);assertEquals(200,listed.getStatus());assertEquals(1,names);
        var row=(Map<?,?>)c.detail(id.toString()).getEntity();
        assertEquals(Set.of("clinicalPatientId","status","createdAt","updatedAt","nameStatus","firstName","lastName","strokeDiagnosis","sequelae"),row.keySet());
        assertNull(row.get("firstName"));assertFalse(row.toString().contains("private-subject"));
    }
    @Test void wrongRoleNeverReadsAndUnknownDetailDoesNotEnrich() {
        var bad=new AssignedPatientRequestSupportTest().identity(false,Set.of("PATIENT"),"patient");
        assertEquals(403,new DoctorClinicalPatientController(service(),bad,mapper).list(0,20).getStatus());assertEquals(0,reads);
        var good=new AssignedPatientRequestSupportTest().identity(false,Set.of("DOCTOR"),"doctor");
        assertEquals(403,new DoctorClinicalPatientController(service(),good,mapper).detail(new UUID(0,2).toString()).getStatus());assertEquals(0,names);
    }
    @Test void authorizationCallbackIsClosedAndNeverCallsProfile() {
        var good=new AssignedPatientRequestSupportTest().identity(false,Set.of("DOCTOR"),"doctor");
        var c=new PatientNameAuthorizationController(service(),good,mapper);
        var response=c.authorize("{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"],\"patientSubjects\":{\""+id+"\":\"private-subject\"}}");
        assertEquals(200,response.getStatus());assertEquals(0,names);assertEquals(1,reads);
        assertFalse(response.getEntity().toString().contains("private-subject"));
        assertEquals(400,c.authorize("{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"]}").getStatus());
        assertEquals(403,c.authorize("{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"],\"patientSubjects\":{\""+id+"\":\"forged-subject\"}}").getStatus());
        assertEquals(0,names);
        assertEquals(400,c.authorize("{\"doctorSubject\":\"spoof\"}").getStatus());assertEquals(2,reads);
    }
}
