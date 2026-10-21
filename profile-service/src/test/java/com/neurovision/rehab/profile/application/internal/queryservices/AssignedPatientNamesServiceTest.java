package com.neurovision.rehab.profile.application.internal.queryservices;

import com.neurovision.rehab.profile.application.ports.out.persistence.PatientNameReadPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientNamesServiceTest {
    final UUID id = new UUID(0,1);
    int reads;
    @Test void clinicalDenialStopsBeforeAnyProfileRead() {
        var s = new AssignedPatientNamesService((ids,token)->{throw new PatientNameFailure(PatientNameFailure.Kind.FORBIDDEN);}, subjects->{reads++;return Map.of();});
        assertEquals(PatientNameFailure.Kind.FORBIDDEN,assertThrows(PatientNameFailure.class,()->s.lookup(Map.of(id,"patient"),"token")).kind());
        assertEquals(0,reads);
    }
    @Test void exactAuthorizationRequiredBeforeReadingNames() {
        var s = new AssignedPatientNamesService((ids,token)->Map.of(), subjects->{reads++;return Map.of();});
        assertThrows(PatientNameFailure.class,()->s.lookup(Map.of(id,"patient"),"token"));assertEquals(0,reads);
    }
    @Test void changedClaimedSubjectCannotSelectAnUnrelatedProfile() {
        var s=new AssignedPatientNamesService((targets,token)->Map.of(id,"different-subject"),subjects->{reads++;return Map.of();});
        assertThrows(PatientNameFailure.class,()->s.lookup(Map.of(id,"patient"),"token"));assertEquals(0,reads);
    }
    @Test void namesPendingAndMissingAreExplicitAndNoOtherFieldsReturned() {
        for (var entry : List.of(new PatientNameReadPort.Entry("PATIENT","ACTIVE","Synthetic","Patient"),new PatientNameReadPort.Entry("PATIENT","PENDING_COMPLETION",null,null))) {
            var s=new AssignedPatientNamesService((ids,token)->Map.of(id,"patient"),subjects->Map.of("patient",entry));
            var n=s.lookup(Map.of(id,"patient"),"token").get(id);
            assertEquals(entry.status().equals("ACTIVE")?"AVAILABLE":"PENDING_COMPLETION",n.status());
            assertEquals(entry.firstName(),n.firstName());
            assertFalse(n.toString().contains("Synthetic"));
        }
        var s=new AssignedPatientNamesService((ids,token)->Map.of(id,"patient"),subjects->Map.of());
        assertEquals("MISSING",s.lookup(Map.of(id,"patient"),"token").get(id).status());
    }
    @Test void malformedRequestsNeverAuthorizeOrRead() {
        var s=new AssignedPatientNamesService((ids,token)->{fail("authorization called");return Map.of();},subjects->{fail("read called");return Map.of();});
        for(var targets:List.<Map<UUID,String>>of(Map.of(),Map.of(id," "))) assertThrows(PatientNameFailure.class,()->s.lookup(targets,"token"));
        assertThrows(PatientNameFailure.class,()->s.lookup(Map.of(id,"patient")," "));
    }
    @Test void wrongProfileTypeAndMalformedNamesFailClosed() {
        for(var entry:List.of(new PatientNameReadPort.Entry("DOCTOR","ACTIVE","S","P"),new PatientNameReadPort.Entry("PATIENT","ACTIVE",null,null),new PatientNameReadPort.Entry("PATIENT","PENDING_COMPLETION","S","P"))) {
            var s=new AssignedPatientNamesService((ids,token)->Map.of(id,"patient"),subjects->Map.of("patient",entry));
            assertEquals(PatientNameFailure.Kind.UNAVAILABLE,assertThrows(PatientNameFailure.class,()->s.lookup(Map.of(id,"patient"),"token")).kind());
        }
    }
}
