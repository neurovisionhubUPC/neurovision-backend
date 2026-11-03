package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/analytics/sessions/{therapySessionId}/visual-search/summary")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class VisualSearchTrialSummaryController {
    private final VisualSearchTrialSummaryService service;
    private final SecurityIdentity identity;

    @Inject
    public VisualSearchTrialSummaryController(VisualSearchTrialSummaryService service,SecurityIdentity identity) {
        this.service = Objects.requireNonNull(service); this.identity = identity;
    }

    @GET @Blocking @RolesAllowed("DOCTOR")
    @Operation(operationId = "getVisualSearchTrialSummary", summary = "Read observed Visual Search trial metrics",
            description = "DOCTOR only; original token and fresh Clinical authorship/active assignment before MongoDB. Required dataOrigin SYNTHETIC or PRODUCER_REPORTED. Frozen policy, at most 1000 results, no pagination or partial summary. Empty authorized observation is 200 with zero counts and null median. 400 invalid query, 401 invalid token, 403 denied, 422 limit exceeded, 503 unavailable. No-store; not session completeness, therapeutic efficacy, a report or snapshot.")
    public Uni<Response> read(@PathParam("therapySessionId") String rawSession,@QueryParam("dataOrigin") String rawOrigin) {
        if (identity == null || identity.isAnonymous()) return item(401,"INVALID_TOKEN");
        if (!Set.of("DOCTOR").equals(identity.getRoles())) return item(403,"DENIED");
        var credential = identity.getCredential(AccessTokenCredential.class);
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank())
            return item(401,"INVALID_TOKEN");
        final UUID session; final DataOrigin origin;
        try {
            session = UUID.fromString(rawSession);
            if (!session.toString().equalsIgnoreCase(rawSession)) throw new IllegalArgumentException();
            origin = DataOrigin.valueOf(rawOrigin);
        } catch (RuntimeException invalid) { return item(400,"INVALID_REQUEST"); }
        return service.query(session,origin,credential.getToken()).map(result -> switch (result.status()) {
            case AVAILABLE -> Response.ok(VisualSearchTrialSummaryResponse.from(result.summary()))
                    .header("Cache-Control","no-store").build();
            case INVALID_QUERY -> response(400,"INVALID_REQUEST");
            case INVALID_TOKEN -> response(401,"INVALID_TOKEN");
            case DENIED -> response(403,"DENIED");
            case LIMIT_EXCEEDED -> response(422,"LIMIT_EXCEEDED");
            case UNAVAILABLE -> response(503,"UNAVAILABLE");
        }).onFailure().recoverWithItem(ignored -> response(503,"UNAVAILABLE"));
    }
    private static Uni<Response> item(int status,String code) { return Uni.createFrom().item(response(status,code)); }
    private static Response response(int status,String code) {
        var response = Response.status(status).entity(Map.of("code","VISUAL_SEARCH_SUMMARY_"+code))
                .header("Cache-Control","no-store");
        if (status == 401) response.header("WWW-Authenticate","Bearer");
        return response.build();
    }
}
