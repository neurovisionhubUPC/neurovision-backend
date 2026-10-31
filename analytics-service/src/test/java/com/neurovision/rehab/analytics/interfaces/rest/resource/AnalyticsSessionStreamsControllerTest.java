package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryAnalyticsSessionStreamsService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort.Item;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalyticsSessionStreamsControllerTest {
    static final String SESSION="00000000-0000-0000-0000-000000000001";
    static SecurityIdentity identity(Set<String> roles,boolean anonymous,boolean credential) {
        return (SecurityIdentity)Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(),new Class[]{SecurityIdentity.class},(p,m,a) -> switch(m.getName()) {
            case "isAnonymous" -> anonymous; case "getRoles" -> roles; case "hasRole" -> roles.contains(a[0]);
            case "getCredential" -> credential ? new AccessTokenCredential("original") : null;
            default -> throw new AssertionError(m.getName());
        });
    }
    static QueryAnalyticsSessionStreamsService service() {
        return new QueryAnalyticsSessionStreamsService((id,t) -> Decision.PERMITTED,(id,p) -> Uni.createFrom().item(List.of(new Item(new UUID(0,2),"baseline-v1",3,20,10))));
    }
    static void error(AnalyticsSessionStreamsController c,String id,String page,String size,int status) {
        try(var r=c.read(id,page,size).await().indefinitely()) {
            assertEquals(status,r.getStatus()); assertEquals("no-store",r.getHeaderString("Cache-Control"));
            assertEquals(Set.of("code"),((Map<?,?>)r.getEntity()).keySet());
            assertEquals(switch(status){case 400 -> "STREAM_DISCOVERY_INVALID_REQUEST";case 401 -> "UNAUTHENTICATED";case 403 -> "STREAM_DISCOVERY_FORBIDDEN";default -> "STREAM_DISCOVERY_UNAVAILABLE";},((Map<?,?>)r.getEntity()).get("code"));
        }
    }
    @Test void onlyPureDoctorReachesApplicationAndAllErrorsAreNoStore() {
        var denied = new QueryAnalyticsSessionStreamsService((id,t) -> {fail("Unexpected Clinical call");return null;},(id,p) -> {fail("Unexpected Mongo call");return null;});
        for(var roles:List.of(Set.of("PATIENT"),Set.of("ADMIN"),Set.of("DOCTOR","PATIENT"),Set.of("DOCTOR","ADMIN"),Set.<String>of()))
            error(new AnalyticsSessionStreamsController(denied,identity(roles,false,true)),SESSION,null,null,403);
        error(new AnalyticsSessionStreamsController(denied,identity(Set.of(),true,false)),SESSION,null,null,401);
        error(new AnalyticsSessionStreamsController(denied,identity(Set.of("DOCTOR"),false,false)),SESSION,null,null,401);
    }
    @Test void validatesCanonicalUuidAndStrictPagingWithoutCallingClinical() {
        var denied = new QueryAnalyticsSessionStreamsService((id,t) -> {fail("Invalid input reached Clinical");return null;},(id,p) -> null);
        var c=new AnalyticsSessionStreamsController(denied,identity(Set.of("DOCTOR"),false,true));
        for(String id:List.of("bad","1-1-1-1-1")) error(c,id,null,null,400);
        for(String[] pair:new String[][]{{"-1","20"},{"0","0"},{"0","51"},{"21","50"},{"2147483648","20"},{"1.0","20"},{" ","20"}}) error(c,SESSION,pair[0],pair[1],400);
    }
    @Test void exposesOnlyAgreedMetadataWithDefaultsAndNoStore() throws Exception {
        var c=new AnalyticsSessionStreamsController(service(),identity(Set.of("DOCTOR"),false,true));
        try(var r=c.read(SESSION,null,null).await().indefinitely()) {
            assertEquals(200,r.getStatus());assertEquals("no-store",r.getHeaderString("Cache-Control"));
            var json=new ObjectMapper().valueToTree(r.getEntity());
            assertEquals(Set.of("therapySessionId","page","size","hasNext","items"),names(json));
            assertEquals(0,json.get("page").intValue());assertEquals(20,json.get("size").intValue());assertFalse(json.get("hasNext").booleanValue());
            var item=json.get("items").get(0);assertEquals(Set.of("streamId","calculationVersion","revision","sampleCounts"),names(item));
            assertEquals(Set.of("gaze","gameplay","total"),names(item.get("sampleCounts")));assertEquals(30,item.get("sampleCounts").get("total").longValue());
            assertNotNull(AnalyticsSessionStreamsResponse.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class));
            assertNotNull(AnalyticsSessionStreamsResponse.Item.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class));
            assertNotNull(AnalyticsSessionStreamsResponse.SampleCounts.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class));
        }
    }
    static Set<String> names(com.fasterxml.jackson.databind.JsonNode node){var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names;}
    @Test void mapsDeniedInvalidTokenAndUnavailableWithoutDetails() {
        for(var d:List.of(Decision.DENIED,Decision.INVALID_TOKEN,Decision.UNAVAILABLE)){
            var s=new QueryAnalyticsSessionStreamsService((id,t)->d,(id,p)->{fail("Denied Mongo read");return null;});
            error(new AnalyticsSessionStreamsController(s,identity(Set.of("DOCTOR"),false,true)),SESSION,"0","20",d==Decision.DENIED?403:d==Decision.INVALID_TOKEN?401:503);
        }
    }
    @Test void emptyPageIs200AndEndpointIsGetDoctorOnlyWithoutTouchingOldRoutes() throws Exception {
        var s=new QueryAnalyticsSessionStreamsService((id,t)->Decision.PERMITTED,(id,p)->Uni.createFrom().item(List.of()));
        var c=new AnalyticsSessionStreamsController(s,identity(Set.of("DOCTOR"),false,true));
        try(var r=c.read(SESSION,"1000","1").await().indefinitely()) {
            var dto=assertInstanceOf(AnalyticsSessionStreamsResponse.class,r.getEntity());assertEquals(200,r.getStatus());assertTrue(dto.items().isEmpty());assertFalse(dto.hasNext());assertEquals(1000,dto.page());
        }
        var method=AnalyticsSessionStreamsController.class.getMethod("read",String.class,String.class,String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
        assertArrayEquals(new String[]{"DOCTOR"},method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertEquals("/api/v1/analytics/sessions/{therapySessionId}/streams",AnalyticsSessionStreamsController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
    }
}
