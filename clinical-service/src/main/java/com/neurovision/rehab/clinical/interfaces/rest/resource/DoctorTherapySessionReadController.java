package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapySessionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.interfaces.rest.transform.DoctorTherapySessionReadRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorTherapySessionReadController {
    private final DoctorTherapySessionQueryService queries;
    private final SecurityIdentity identity;
    private final AssignedPatientRequestSupport support;
    public DoctorTherapySessionReadController(DoctorTherapySessionQueryService queries,SecurityIdentity identity,ObjectMapper mapper) {
        this.queries=queries; this.identity=identity; this.support=new AssignedPatientRequestSupport(mapper);
    }

    @GET @RolesAllowed("DOCTOR")
    @Operation(summary="List owned sessions for a currently assigned patient",
            description="Page 0, size 20 (1–100); exact exerciseCode/status; timezone-explicit from inclusive/to exclusive on startedAt. Fixed startedAt and session UUID descending. Persisted state, duration and frozen configuration references only; no Profile/CMP dependency or GET mutations.")
    public Response list(@PathParam("clinicalPatientId") String patient,
            @QueryParam("page") @DefaultValue("0") String page,
            @QueryParam("size") @DefaultValue("20") String size,
            @QueryParam("exerciseCode") String exerciseCode,@QueryParam("status") String status,
            @QueryParam("from") String from,@QueryParam("to") String to) {
        try {
            var actor=support.actor(identity);
            if(identity.hasRole("ADMIN")) throw new SecurityException();
            var result=queries.list(actor.subject(),uuid(patient),number(page),number(size),exerciseCode,status,from,to);
            return Response.ok(DoctorTherapySessionReadRestAssembler.page(result)).header("Cache-Control","no-store").build();
        } catch(PatientReadFailure failure) {
            return switch(failure.kind()) {
                case UNAUTHENTICATED -> error(401,"UNAUTHENTICATED");
                case FORBIDDEN -> error(403,"FORBIDDEN");
                case INVALID_REQUEST -> error(400,"INVALID_REQUEST");
                case UNAVAILABLE -> error(503,"UNAVAILABLE");
            };
        } catch(SecurityException denied) { return error(403,"FORBIDDEN"); }
        catch(IllegalArgumentException invalid) { return error(400,"INVALID_REQUEST"); }
        catch(RuntimeException unavailable) { return error(503,"UNAVAILABLE"); }
    }
    private static UUID uuid(String raw) {
        if(raw==null) throw new IllegalArgumentException();
        var id=UUID.fromString(raw);
        if(!id.toString().equals(raw)) throw new IllegalArgumentException();
        return id;
    }
    private static int number(String raw) {
        if(raw==null || !raw.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
        return Integer.parseInt(raw);
    }
    private static Response error(int status,String kind) {
        return Response.status(status).entity(Map.of("code","SESSION_READ_"+kind)).header("Cache-Control","no-store").build();
    }
}
