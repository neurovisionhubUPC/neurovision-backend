package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotListRepository.Summary;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JpaSessionReportSnapshotListTest extends JpaSessionReportSnapshotRepositoryTest {
    @Test void listSharesCurrentAuthorizationTotalAndMetadataPageWithoutLoadingOrMutatingReports() {
        results.add(Collections.singletonList(new Object[]{true, 3L, id, OffsetDateTime.ofInstant(time,ZoneOffset.UTC)}));
        var value=repository().observeList("doctor",patient,session,2,2);
        assertTrue(value.authorized()); assertEquals(3,value.total()); assertEquals(List.of(new Summary(id,time)),value.snapshots());
        assertEquals(1,sql.size()); assertEquals(0,mutations);
        assertEquals(Map.of("doctor","doctor","patient",patient,"session",session,"offset",2,"limit",2),parameters.getFirst());
        for(String fragment:List.of("WITH authorized AS", "s.id = :session", "s.clinical_patient_id = :patient",
                "s.doctor_cognito_sub = :doctor", "a.status = 'ACTIVE'", "a.doctor_cognito_sub = s.doctor_cognito_sub",
                "r.clinical_patient_id = :patient", "r.therapy_session_id = :session", "r.doctor_cognito_sub = :doctor",
                "(SELECT allowed FROM authorized)", "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                "count(*) FROM filtered", "LEFT JOIN page", "ORDER BY p.created_at DESC, p.id DESC"))
            assertTrue(sql.getFirst().contains(fragment),fragment);
        for(String forbidden:List.of("report_json","idempotency_key","COMPLETED","FOR UPDATE","INSERT","UPDATE ","DELETE"))
            assertFalse(sql.getFirst().contains(forbidden),forbidden);
    }
    @Test void listDistinguishesAuthorizedEmptyAndOutOfRangeFromDenial() {
        var repo=repository();
        for(var row:List.of(new Object[]{true,0L,null,null},new Object[]{true,5L,null,null},new Object[]{false,0L,null,null})) {
            results.add(Collections.singletonList(row));
            var result=repo.observeList("doctor",patient,session,10,2);
            assertEquals(row[0],result.authorized()); assertEquals(row[1],result.total()); assertTrue(result.snapshots().isEmpty());
        }
        assertEquals(0,mutations);
    }
    @Test void malformedListRowsAndDeniedMetadataFailClosed() {
        var repo=repository();
        for(List<?> rows:List.<List<?>>of(List.of(),Collections.singletonList(new Object[]{true}),
                Collections.singletonList(new Object[]{false,1L,id,time}), Collections.singletonList(new Object[]{true,-1L,null,null}),
                Collections.singletonList(new Object[]{true,0L,null,time}),
                List.of(new Object[]{true,2L,id,time},new Object[]{false,2L,new UUID(0,5),time}),
                List.of(new Object[]{true,2L,id,time},new Object[]{true,3L,new UUID(0,5),time}),
                List.of(new Object[]{true,1L,null,null},new Object[]{true,1L,id,time}))) {
            results.add(rows); assertThrows(RuntimeException.class,()->repo.observeList("doctor",patient,session,0,2));
        }
        assertEquals(0,mutations);
    }
    @Test void invalidRepositoryPageNeverQueries() {
        var repo=repository();
        for(int[] v:List.of(new int[]{-1,20},new int[]{0,0},new int[]{0,101}))
            assertThrows(IllegalArgumentException.class,()->repo.observeList("doctor",patient,session,v[0],v[1]));
        assertTrue(sql.isEmpty());
    }
}
