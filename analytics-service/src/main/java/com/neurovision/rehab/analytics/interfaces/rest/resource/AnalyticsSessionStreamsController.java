package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryAnalyticsSessionStreamsService;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSessionStreamsQueryPort.Page;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/analytics/sessions/{therapySessionId}/streams")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class AnalyticsSessionStreamsController {
    private final QueryAnalyticsSessionStreamsService service;
    private final SecurityIdentity identity;
    public AnalyticsSessionStreamsController(QueryAnalyticsSessionStreamsService service, SecurityIdentity identity) {
        this.service=service; this.identity=identity;
    }
    @GET @Blocking @RolesAllowed("DOCTOR")
    @Operation(operationId="getAnalyticsSessionStreams",summary="Discover observed baseline-v1 projections",
            description="DOCTOR only; Clinical verifies session authorship and active assignment before Mongo. Page defaults 0/20, size 1-50, offset at most 1000. Stream IDs ascending; hasNext without total or snapshot guarantee. 200/400/401/403/503, no-store. Not Telemetry completeness or report readiness.")
    public Uni<Response> read(@PathParam("therapySessionId") String rawSession,
            @QueryParam("page") String rawPage, @QueryParam("size") String rawSize) {
        if (identity==null || identity.isAnonymous()) return error(401,"UNAUTHENTICATED");
        if (!Set.of("DOCTOR").equals(identity.getRoles())) return error(403,"STREAM_DISCOVERY_FORBIDDEN");
        var credential=identity.getCredential(AccessTokenCredential.class);
        if (credential==null || credential.getToken()==null || credential.getToken().isBlank()) return error(401,"UNAUTHENTICATED");
        final UUID session; final Page page;
        try {
            session=UUID.fromString(rawSession);
            if (!session.toString().equalsIgnoreCase(rawSession)) throw new IllegalArgumentException();
            page=new Page(integer(rawPage,0),integer(rawSize,20));
        } catch (RuntimeException invalid) { return error(400,"STREAM_DISCOVERY_INVALID_REQUEST"); }
        return service.query(session,page,credential.getToken()).map(outcome -> {
            if (outcome.status()==QueryAnalyticsSessionStreamsService.Status.OK) {
                var items=outcome.items().stream().map(item -> new AnalyticsSessionStreamsResponse.Item(
                        item.streamId().toString(),item.calculationVersion(),item.revision(),
                        new AnalyticsSessionStreamsResponse.SampleCounts(item.gaze(),item.gameplay(),item.total()))).toList();
                return Response.ok(new AnalyticsSessionStreamsResponse(session.toString(),page.page(),page.size(),outcome.hasNext(),items))
                        .header("Cache-Control","no-store").build();
            }
            return switch(outcome.status()) {
                case INVALID_REQUEST -> response(400,"STREAM_DISCOVERY_INVALID_REQUEST");
                case UNAUTHENTICATED -> response(401,"UNAUTHENTICATED");
                case FORBIDDEN -> response(403,"STREAM_DISCOVERY_FORBIDDEN");
                default -> response(503,"STREAM_DISCOVERY_UNAVAILABLE");
            };
        });
    }
    private static int integer(String value,int fallback) {
        if(value==null)return fallback;
        if(!value.matches("[0-9]{1,10}"))throw new IllegalArgumentException();
        return Integer.parseInt(value);
    }
    private static Uni<Response> error(int status,String code) { return Uni.createFrom().item(response(status,code)); }
    private static Response response(int status,String code) {
        return Response.status(status).entity(Map.of("code",code)).header("Cache-Control","no-store").build();
    }
}
