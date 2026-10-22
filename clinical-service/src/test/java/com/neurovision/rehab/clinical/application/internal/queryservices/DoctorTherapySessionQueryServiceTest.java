package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapySessionQueryServiceTest {
    final UUID patient = new UUID(0, 1);
    Filters observed;
    int calls, offset, limit;
    Observation observation = new Observation(true, List.of(), 7);
    boolean broken;
    final DoctorTherapySessionQueryService service = new DoctorTherapySessionQueryService((p, d, f, o, l) -> {
        calls++; assertEquals(patient, p); assertEquals("doctor", d);
        observed=f; offset=o; limit=l;
        if (broken) throw new IllegalArgumentException("private persisted data");
        return observation;
    });

    @Test void combinesFiltersAndPreservesEmptyPageTotal() {
        var result=service.list("doctor",patient,2,20,"fixation","COMPLETED",
                "2026-10-01T07:00:00-05:00","2026-10-02T12:00:00Z");
        assertEquals(40,offset); assertEquals(20,limit); assertEquals(1,calls);
        assertEquals("fixation",observed.exerciseCode()); assertEquals("COMPLETED",observed.status().name());
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"),observed.from());
        assertEquals(Instant.parse("2026-10-02T12:00:00Z"),observed.to());
        assertEquals(7,result.total()); assertTrue(result.sessions().isEmpty());
    }
    @Test void defaultFiltersAndAllCanonicalValuesAreAccepted() {
        service.list("doctor",patient,0,100,null,null,null,null);
        assertEquals(new Filters(null,null,null,null),observed); assertEquals(100,limit);
        for(String exercise:List.of("fixation","visual-scanning","visual-search"))
            for(String status:List.of("ACTIVE","COMPLETED","CANCELLED","ABANDONED","FAILED_TECHNICAL"))
                service.list("doctor",patient,0,20,exercise,status,null,null);
        service.list("doctor",patient,Integer.MAX_VALUE,1,null,null,null,null);
        assertEquals(Integer.MAX_VALUE,offset);
    }
    @Test void invalidInputNeverReachesPersistence() {
        for(int[] b:List.of(new int[]{-1,20},new int[]{0,0},new int[]{0,101},new int[]{Integer.MAX_VALUE,2}))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",patient,b[0],b[1],null,null,null,null));
        for(String exercise:List.of("FIXATION"," fixation","fixation ","","unknown"))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",patient,0,20,exercise,null,null,null));
        for(String status:List.of("active"," ACTIVE","","UNKNOWN"))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",patient,0,20,null,status,null,null));
        for(String date:List.of("2026-10-01","2026-10-01T12:00:00","invalid","2026-02-30T00:00:00Z"))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",patient,0,20,null,null,date,null));
        for(String end:List.of("2026-10-01T12:00:00Z","2026-10-01T11:00:00Z"))
            assertThrows(IllegalArgumentException.class,()->service.list("doctor",patient,0,20,null,null,"2026-10-01T12:00:00Z",end));
        assertEquals(0,calls);
    }
    @Test void unknownOrRevokedAssignmentFailsClosedAndStorageFailureIsNotBadInput() {
        observation=new Observation(false,List.of(),0);
        assertThrows(SecurityException.class,()->service.list("doctor",patient,0,20,null,null,null,null));
        broken=true;
        assertThrows(IllegalStateException.class,()->service.list("doctor",patient,0,20,null,null,null,null));
    }
    @Test void frozenReferencesAndPersistedDurationAreReturnedWithoutLifecycleInference() {
        var row=new Session(new UUID(0,2),new UUID(0,3),"fixation","ACTIVE",
                Instant.parse("2020-01-01T00:00:00Z"),null,17,LocalDate.of(2020,1,1),new UUID(0,4),2,1);
        observation=new Observation(true,List.of(row),1);
        var result=service.list("doctor",patient,0,20,null,null,null,null);
        assertEquals(List.of(row),result.sessions()); assertEquals(17,result.sessions().getFirst().activeDurationSeconds());
        assertNull(result.sessions().getFirst().endedAt());
        assertThrows(UnsupportedOperationException.class,()->result.sessions().clear());
    }
}
