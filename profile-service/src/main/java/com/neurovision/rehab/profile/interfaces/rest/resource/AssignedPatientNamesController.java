package com.neurovision.rehab.profile.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.profile.application.internal.queryservices.AssignedPatientNamesService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/internal/v1/profiles/assigned-patient-names")
@Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RequestScoped
public class AssignedPatientNamesController {
    private final AssignedPatientNamesService names;
    private final SecurityIdentity identity;
    private final AssignedPatientRequestSupport support;
    public AssignedPatientNamesController(AssignedPatientNamesService names,SecurityIdentity identity,ObjectMapper mapper) {
        this.names=names;this.identity=identity;support=new AssignedPatientRequestSupport(mapper);
    }
    @POST @RolesAllowed("DOCTOR")
    @Operation(summary="Read names for exactly authorized Clinical patients",description="Revalidates the original DOCTOR access token and asks Clinical for exact current assignments before Profile reads. Returns names only, without subjects, documents, birth dates or contact information.")
    public Response lookup(String body) {
        return support.response(()->{
            var actor=support.actor(identity);var targets=support.targets(body);var ids=List.copyOf(targets.keySet());var found=names.lookup(targets,actor.token());
            var rows=new ArrayList<Map<String,Object>>();
            for(var id:ids) {
                var name=found.get(id);var row=new LinkedHashMap<String,Object>();
                row.put("clinicalPatientId",id);row.put("nameStatus",name.status());row.put("firstName",name.firstName());row.put("lastName",name.lastName());rows.add(row);
            }
            return Map.of("contractVersion",1,"patients",rows);
        });
    }
}
