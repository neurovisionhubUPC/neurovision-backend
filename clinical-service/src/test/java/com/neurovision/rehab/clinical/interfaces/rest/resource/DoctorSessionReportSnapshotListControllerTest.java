package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository.*;
import com.neurovision.rehab.clinical.infrastructure.reporting.PdfBoxSessionReportRenderer;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.*;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSessionReportSnapshotListControllerTest extends DoctorSessionReportSnapshotControllerTest {
    int listReads;
    final SessionReportSnapshotListRepository listRepo = (d,p,s,o,l) -> {
        listReads++; assertEquals(DOCTOR,d);
        if(f.failRead) throw new IllegalStateException("private SQL detail");
        if(!f.allowed || !PATIENT.equals(p) || !SESSION.equals(s)) return new ListObservation(false,List.of(),0);
        var rows=f.copies.values().stream().map(v->new Summary(v.id(),v.createdAt()))
                .sorted(Comparator.comparing(Summary::createdAt).thenComparing(v->v.reportSnapshotId().toString()).reversed()).toList();
        return new ListObservation(true,rows.subList(Math.min(o,rows.size()),(int)Math.min((long)o+l,rows.size())),rows.size());
    };
    @Override DoctorSessionReportSnapshotController controller(SecurityIdentity identity) {
        return new DoctorSessionReportSnapshotController(f.service(),new GetSessionReportSnapshotService(f),identity,f.mapper,
                new PdfBoxSessionReportRenderer(f.mapper),new ListSessionReportSnapshotsService(listRepo));
    }
    Response list(DoctorSessionReportSnapshotController c,String page,String size) { return c.list(PATIENT.toString(),SESSION.toString(),page,size); }
    @Test void listWhitelistsOnlyMetadataWithStablePagesAndNeverChangesCopiesOrUsesPeers() {
        f.create("first");f.create("second");f.create("third");
        int peers=f.peers,writes=f.writes;var before=Map.copyOf(f.copies);
        var expected=f.copies.values().stream().map(v->v.id().toString()).sorted(Comparator.reverseOrder()).toList();
        var response=list(doctor(),"0","2"); assertEquals(200,response.getStatus());
        assertEquals("no-store",response.getHeaderString("Cache-Control"));var json=json(response);
        assertEquals(Set.of("clinicalPatientId","therapySessionId","snapshots","page","size","total"),fieldNames(json));
        assertEquals(PATIENT.toString(),json.path("clinicalPatientId").asText());assertEquals(SESSION.toString(),json.path("therapySessionId").asText());
        assertEquals(3,json.path("total").asLong());assertEquals(2,json.path("snapshots").size());
        for(int i=0;i<2;i++) {
            var item=json.path("snapshots").get(i);assertEquals(Set.of("reportSnapshotId","createdAt"),fieldNames(item));
            assertEquals(expected.get(i),item.path("reportSnapshotId").asText());assertEquals(TIME.toString(),item.path("createdAt").asText());
        }
        assertEquals(expected.get(2),json(list(doctor(),"1","2")).path("snapshots").get(0).path("reportSnapshotId").asText());
        var beyond=json(list(doctor(),"2","2"));assertTrue(beyond.path("snapshots").isEmpty());assertEquals(3,beyond.path("total").asLong());
        assertFalse(json.toString().contains("reportJson"));assertFalse(json.toString().contains(DOCTOR));assertFalse(json.toString().contains(TOKEN));
        assertEquals(before,f.copies);assertEquals(peers,f.peers);assertEquals(writes,f.writes);
    }
    @Test void emptyDeniedAndUnavailableRemainDistinctAndGeneric() {
        var empty=list(doctor(),"0","20");assertEquals(200,empty.getStatus());assertTrue(json(empty).path("snapshots").isEmpty());
        f.allowed=false;error(list(doctor(),"0","20"),403,"FORBIDDEN");f.allowed=true;
        error(doctor().list(UUID.randomUUID().toString(),SESSION.toString(),"0","20"),403,"FORBIDDEN");
        error(doctor().list(PATIENT.toString(),UUID.randomUUID().toString(),"0","20"),403,"FORBIDDEN");
        f.failRead=true;error(list(doctor(),"0","20"),503,"UNAVAILABLE");assertEquals(0,f.peers);assertEquals(0,f.writes);
    }
    @Test void roleAndInputValidationPrecedeListing() {
        error(list(controller(null),"bad","bad"),401,"UNAUTHENTICATED");
        for(var roles:List.of(Set.of("PATIENT"),Set.of("ADMIN"),Set.of("DOCTOR","PATIENT"),Set.of("DOCTOR","ADMIN")))
            error(list(controller(identity(false,roles,DOCTOR,TOKEN,true)),"0","20"),403,"FORBIDDEN");
        for(String value:Arrays.asList(null,"", "-1","1.2","2147483648","12345678901"))
            error(list(doctor(),value,"20"),400,"INVALID_REQUEST");
        for(String value:Arrays.asList(null,"","0","101","x")) error(list(doctor(),"0",value),400,"INVALID_REQUEST");
        error(list(doctor(),"2147483647","100"),400,"INVALID_REQUEST");
        for(String value:Arrays.asList(null,"bad","1-1-1-1-1","00000000-0000-0000-0000-00000000000A")) {
            error(doctor().list(value,SESSION.toString(),"0","20"),400,"INVALID_REQUEST");
            error(doctor().list(PATIENT.toString(),value,"0","20"),400,"INVALID_REQUEST");
        }
        assertEquals(0,listReads);assertEquals(0,f.peers);assertEquals(0,f.writes);
    }
    @Test void listRouteIsDoctorOnlyWithExistingDefaults() throws Exception {
        var m=DoctorSessionReportSnapshotController.class.getMethod("list",String.class,String.class,String.class,String.class);
        assertNotNull(m.getAnnotation(jakarta.ws.rs.GET.class));assertNull(m.getAnnotation(jakarta.ws.rs.Path.class));
        assertArrayEquals(new String[]{"DOCTOR"},m.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertEquals("0",m.getParameters()[2].getAnnotation(jakarta.ws.rs.DefaultValue.class).value());
        assertEquals("20",m.getParameters()[3].getAnnotation(jakarta.ws.rs.DefaultValue.class).value());
    }
}
