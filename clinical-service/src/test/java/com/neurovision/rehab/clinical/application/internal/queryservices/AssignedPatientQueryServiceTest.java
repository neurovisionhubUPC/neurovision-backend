package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.AssignedPatientReadPort;
import com.neurovision.rehab.clinical.application.ports.out.integration.AssignedPatientNamesPort;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssignedPatientQueryServiceTest {
    final UUID own = new UUID(0, 1), other = new UUID(0, 2);
    final Fake reads = new Fake();
    int nameCalls;
    final AssignedPatientNamesPort names = (ids, token) -> {
        nameCalls++;
        assertEquals("original-token", token);
        assertEquals(Map.of(own,"patient-subject"), ids);
        return Map.of(own, new AssignedPatientNamesPort.Name("AVAILABLE", "Synthetic", "Patient"));
    };
    AssignedPatientQueryService service() { return new AssignedPatientQueryService(reads, names); }

    @Test void listsOnlyScopedRowsAndEnrichesOneBatch() {
        var page = service().list("doctor", "original-token", 0, 20);
        assertEquals(1, page.total());
        assertEquals(own, page.patients().getFirst().patient().id());
        assertEquals("Synthetic", page.patients().getFirst().name().firstName());
        assertEquals(1, nameCalls);
        assertEquals("doctor", reads.doctor);
    }
    @Test void emptyPageNeverCallsProfile() {
        reads.empty = true;
        assertTrue(service().list("doctor", "original-token", 0, 20).patients().isEmpty());
        assertEquals(0, nameCalls);
    }
    @Test void unknownAndUnassignedDetailAreUniformAndNeverReadNames() {
        var denied = assertThrows(PatientReadFailure.class, () -> service().detail("doctor", "original-token", other));
        assertEquals(PatientReadFailure.Kind.FORBIDDEN, denied.kind());
        assertEquals(0, nameCalls);
    }
    @Test void rejectsBoundsBeforePersistence() {
        for (int[] p : List.of(new int[]{-1,20}, new int[]{0,0}, new int[]{0,101}, new int[]{Integer.MAX_VALUE,100})) {
            assertThrows(PatientReadFailure.class, () -> service().list("doctor", "original-token",p[0],p[1]));
        }
        assertEquals(0, reads.calls);
    }
    @Test void resolutionIsAllOrNothingAndDoesNotCallProfile() {
        assertEquals(Map.of(own,"patient-subject"), service().authorizeNames("doctor",List.of(own)));
        assertThrows(PatientReadFailure.class, () -> service().authorizeNames("doctor",List.of(own,other)));
        assertThrows(PatientReadFailure.class, () -> service().authorizeNames("doctor",List.of(own,own)));
        assertEquals(0,nameCalls);
    }
    @Test void incompleteOrForeignNameResponseFailsClosed() {
        var s = new AssignedPatientQueryService(reads,(ids,token)->Map.of(other,new AssignedPatientNamesPort.Name("MISSING",null,null)));
        assertEquals(PatientReadFailure.Kind.UNAVAILABLE,assertThrows(PatientReadFailure.class,()->s.list("doctor","original-token",0,20)).kind());
    }
    static class Fake implements AssignedPatientReadPort {
        String doctor; int calls; boolean empty;
        Patient row() { return new Patient(new UUID(0,1),"PENDING_ASSESSMENT",Instant.EPOCH,Instant.EPOCH,null,List.of()); }
        public Page list(String d,int offset,int size) { doctor=d;calls++;return new Page(empty?List.of():List.of(row()),empty?0:1); }
        public Optional<Patient> detail(String d,UUID id) {doctor=d;calls++;return id.equals(row().id())?Optional.of(row()):Optional.empty();}
        public Map<UUID,String> resolve(String d,List<UUID> ids) {doctor=d;calls++;return ids.contains(row().id())?Map.of(row().id(),"patient-subject"):Map.of();}
    }
}
