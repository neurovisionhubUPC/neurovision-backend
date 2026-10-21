package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AssignedPatientQueryService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/internal/v1/clinical-patients/profile-name-authorization")
@Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RequestScoped
public class PatientNameAuthorizationController {
    private final AssignedPatientQueryService queries;
    private final SecurityIdentity identity;
    private final AssignedPatientRequestSupport support;
    public PatientNameAuthorizationController(AssignedPatientQueryService queries,SecurityIdentity identity,ObjectMapper mapper) {
        this.queries=queries;this.identity=identity;support=new AssignedPatientRequestSupport(mapper);
    }
    @POST @RolesAllowed("DOCTOR")
    @Operation(summary="Authorize exact assigned patient name targets",description="Delegated DOCTOR access, not service authentication. All-or-nothing ACTIVE assignment authorization; confirms exact claimed bindings without returning subjects. This callback never invokes Profile.")
    public Response authorize(String body) {
        return support.response(()->{
            var actor=support.actor(identity);var targets=support.targets(body);var ids=List.copyOf(targets.keySet());var resolved=queries.authorizeNames(actor.subject(),ids);
            if(!resolved.equals(targets)) throw new com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure(com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure.Kind.FORBIDDEN);
            return Map.of("contractVersion",1,"patients",ids.stream().map(id->Map.of("clinicalPatientId",id)).toList());
        });
    }
}
