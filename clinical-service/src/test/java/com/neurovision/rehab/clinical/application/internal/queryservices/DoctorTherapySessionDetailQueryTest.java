package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExerciseParameters.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapySessionDetailQueryTest {
    final UUID patient=new UUID(0,1),session=new UUID(0,2);
    int calls;
    Optional<Detail> observation=Optional.empty();
    boolean broken;
    final DoctorTherapySessionQueryService queries=new DoctorTherapySessionQueryService(new DoctorTherapySessionReadRepository() {
        public Observation observe(UUID p,String d,Filters f,int o,int l){throw new AssertionError("No authorization/list prequery");}
        public Optional<Detail> observeDetail(UUID p,String d,UUID id){calls++;assertEquals(patient,p);assertEquals(session,id);assertEquals("doctor",d);if(broken)throw new IllegalArgumentException("private storage error");return observation;}
    });
    Detail detail(UUID id) {
        return new Detail(patient,new Session(id,new UUID(0,3),"fixation","ACTIVE",Instant.EPOCH,null,17,LocalDate.of(1970,1,1),new UUID(0,4),2,1),
                new Fixation(FixationMode.SUSTAINED_TARGET_FIXATION,TargetPosition.CENTER,500,1000,50,10,800,2),new SessionLimits(1,10,25),TechnicalProgress.notApplicable());
    }
    @Test void usesOneObservationWithoutLifecycleInference() {
        var value=detail(session);observation=Optional.of(value);
        assertSame(value,queries.detail("doctor",patient,session));assertEquals(1,calls);
        assertEquals("ACTIVE",value.session().status());assertNull(value.session().endedAt());
    }
    @Test void invalidInputNeverReadsAndMissingDetailIsDenied() {
        assertThrows(IllegalArgumentException.class,()->queries.detail("",patient,session));
        assertThrows(NullPointerException.class,()->queries.detail("doctor",null,session));
        assertThrows(NullPointerException.class,()->queries.detail("doctor",patient,null));assertEquals(0,calls);
        assertThrows(SecurityException.class,()->queries.detail("doctor",patient,session));assertEquals(1,calls);
    }
    @Test void invalidStoredObservationNeverLooksLikeClientBadInput() {
        broken=true;assertThrows(IllegalStateException.class,()->queries.detail("doctor",patient,session));broken=false;
        observation=null;assertThrows(IllegalStateException.class,()->queries.detail("doctor",patient,session));
        observation=Optional.of(detail(new UUID(0,99)));assertThrows(IllegalStateException.class,()->queries.detail("doctor",patient,session));
    }
}
