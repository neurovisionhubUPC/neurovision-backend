package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort.*;
import io.smallrye.mutiny.Uni;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryAnalyticsSessionStreamsServiceTest {
    static final UUID SESSION = new UUID(0, 1);
    static Item item(long id) { return new Item(new UUID(0, id), "baseline-v1", 3, 20, 10); }
    @Test void authorizesEveryPageBeforeItsOnlyBoundedRead() {
        var calls = new ArrayList<String>();
        var service = new QueryAnalyticsSessionStreamsService((id, token) -> {
            assertEquals(SESSION, id); assertEquals("original", token); calls.add("authorize"); return Decision.PERMITTED;
        }, (id, page) -> { calls.add("mongo"); assertEquals(new Page(1, 2), page); return Uni.createFrom().item(List.of(item(1), item(2), item(3))); });
        for (int i = 0; i < 2; i++) {
            var result = service.query(SESSION, new Page(1, 2), "original").await().indefinitely();
            assertEquals(QueryAnalyticsSessionStreamsService.Status.OK, result.status());
            assertTrue(result.hasNext()); assertEquals(List.of(item(1), item(2)), result.items());
        }
        assertEquals(List.of("authorize", "mongo", "authorize", "mongo"), calls);
    }
    @Test void deniesOrFailsClosedBeforeMongoForEveryNonPermitAndMissingToken() {
        AnalyticsSessionStreamsQueryPort forbiddenRead = (id, page) -> { fail("Mongo before permit"); return null; };
        for (var decision : Decision.values()) {
            if (decision == Decision.PERMITTED) continue;
            var service = new QueryAnalyticsSessionStreamsService((id, token) -> decision, forbiddenRead);
            var result = service.query(SESSION, new Page(0, 20), "token").await().indefinitely();
            var expected = decision == Decision.DENIED ? QueryAnalyticsSessionStreamsService.Status.FORBIDDEN
                    : decision == Decision.INVALID_TOKEN ? QueryAnalyticsSessionStreamsService.Status.UNAUTHENTICATED
                    : QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE;
            assertEquals(expected, result.status()); assertNull(result.items());
        }
        var service = new QueryAnalyticsSessionStreamsService((id, token) -> { fail("No auth without token"); return null; }, forbiddenRead);
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAUTHENTICATED,
                service.query(SESSION, new Page(0,20), " ").await().indefinitely().status());
        var unavailable = new QueryAnalyticsSessionStreamsService((id, token) -> { throw new IllegalStateException("private"); }, forbiddenRead);
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,
                unavailable.query(SESSION, new Page(0,20), "token").await().indefinitely().status());
    }
    @Test void emptyIsSuccessfulButNullFailureAndInvalidOrderingAreUnavailable() {
        var empty = new QueryAnalyticsSessionStreamsService((id,t) -> Decision.PERMITTED, (id,p) -> Uni.createFrom().item(List.of()));
        var result = empty.query(SESSION,new Page(0,20),"token").await().indefinitely();
        assertEquals(QueryAnalyticsSessionStreamsService.Status.OK,result.status()); assertEquals(List.of(),result.items()); assertFalse(result.hasNext());
        for (var values : List.of(List.of(item(1),item(1)),List.of(item(2),item(1)),List.of(item(1),item(2),item(3),item(4)))) {
            var service = new QueryAnalyticsSessionStreamsService((id,t) -> Decision.PERMITTED,(id,p) -> Uni.createFrom().item(values));
            assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,service.query(SESSION,new Page(0,2),"token").await().indefinitely().status());
        }
        var failure = new QueryAnalyticsSessionStreamsService((id,t) -> Decision.PERMITTED,(id,p) -> Uni.createFrom().failure(new IllegalStateException("private")));
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,failure.query(SESSION,new Page(0,20),"token").await().indefinitely().status());
        var absent = new QueryAnalyticsSessionStreamsService((id,t) -> Decision.PERMITTED,(id,p) -> null);
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,absent.query(SESSION,new Page(0,20),"token").await().indefinitely().status());
    }
    @Test void enforcesExactPagingBoundsAndCountIntegrity() {
        assertEquals(1000,new Page(20,50).offset()); assertEquals(1000,new Page(1000,1).offset());
        for (int[] v : new int[][]{{-1,20},{0,0},{0,51},{21,50},{Integer.MAX_VALUE,50}})
            assertThrows(IllegalArgumentException.class,() -> new Page(v[0],v[1]));
        assertThrows(IllegalArgumentException.class,() -> new Item(new UUID(0,1),"future",1,0,0));
        assertThrows(IllegalArgumentException.class,() -> new Item(new UUID(0,1),"baseline-v1",1,-1,0));
        assertThrows(ArithmeticException.class,() -> new Item(new UUID(0,1),"baseline-v1",1,Long.MAX_VALUE,1));
    }
    @Test void revokedAssignmentOnNextPageDoesNotReusePermit() {
        var calls=new int[2];
        var service=new QueryAnalyticsSessionStreamsService((id,t)->++calls[0]==1?Decision.PERMITTED:Decision.DENIED,
                (id,p)->{calls[1]++;return Uni.createFrom().item(List.of());});
        assertEquals(QueryAnalyticsSessionStreamsService.Status.OK,service.query(SESSION,new Page(0,20),"synthetic").await().indefinitely().status());
        assertEquals(QueryAnalyticsSessionStreamsService.Status.FORBIDDEN,service.query(SESSION,new Page(1,20),"synthetic").await().indefinitely().status());
        assertArrayEquals(new int[]{2,1},calls);
    }
    @Test void nullClinicalDecisionDoesNotReadMongo() {
        var service=new QueryAnalyticsSessionStreamsService((id,t)->null,(id,p)->{fail("Forbidden data access");return null;});
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,service.query(SESSION,new Page(0,20),"synthetic").await().indefinitely().status());
    }
    @Test void synchronousMongoFailureDoesNotExposeDiscoveryMetadata() {
        var service=new QueryAnalyticsSessionStreamsService((id,t)->Decision.PERMITTED,(id,p)->{throw new IllegalStateException("synthetic failure");});
        var result=service.query(SESSION,new Page(0,20),"synthetic").await().indefinitely();
        assertEquals(QueryAnalyticsSessionStreamsService.Status.UNAVAILABLE,result.status());
        assertNull(result.items());assertNull(result.sessionId());assertNull(result.page());assertFalse(result.hasNext());
    }
}
