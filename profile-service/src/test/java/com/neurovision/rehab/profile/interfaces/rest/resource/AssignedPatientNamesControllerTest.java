package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.profile.application.internal.queryservices.*;
import com.neurovision.rehab.profile.application.ports.out.persistence.PatientNameReadPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientNamesControllerTest {
    @Test void validDoctorGetsOnlyNamesWhileDenialHasNoProfileRead() {
        var id=new UUID(0,1);int[] reads={0};
        var service=new AssignedPatientNamesService((ids,token)->Map.of(id,"private-subject"),subjects->{reads[0]++;return Map.of("private-subject",new PatientNameReadPort.Entry("PATIENT","ACTIVE","Synthetic","Patient"));});
        var helper=new AssignedPatientRequestSupportTest();
        var good=helper.identity(false,Set.of("DOCTOR"),"doctor");
        var controller=new AssignedPatientNamesController(service,good,new ObjectMapper());
        var response=controller.lookup("{\"contractVersion\":1,\"clinicalPatientIds\":[\""+id+"\"],\"patientSubjects\":{\""+id+"\":\"private-subject\"}}");
        assertEquals(200,response.getStatus());assertEquals(1,reads[0]);
        var body=(Map<?,?>)response.getEntity();var row=(Map<?,?>)((List<?>)body.get("patients")).getFirst();
        assertEquals(Set.of("clinicalPatientId","nameStatus","firstName","lastName"),row.keySet());assertFalse(row.toString().contains("private-subject"));
        var denied=helper.identity(false,Set.of("DOCTOR","PATIENT"),"doctor");
        assertEquals(403,new AssignedPatientNamesController(service,denied,new ObjectMapper()).lookup("{}").getStatus());assertEquals(1,reads[0]);
    }
}
