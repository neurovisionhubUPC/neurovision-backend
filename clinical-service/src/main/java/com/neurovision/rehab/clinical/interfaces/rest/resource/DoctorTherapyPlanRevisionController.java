package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.interfaces.rest.transform.TherapyPlanRevisionRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Map;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}/revisions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorTherapyPlanRevisionController {
    private final DoctorTherapyPlanRevisionCommandService revisions;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    public DoctorTherapyPlanRevisionController(DoctorTherapyPlanRevisionCommandService revisions,
            SecurityIdentity identity, ObjectMapper mapper) {
        this.revisions = revisions;
        this.identity = identity;
        this.mapper = mapper;
    }

    @POST
    @RolesAllowed("DOCTOR")
    @Operation(summary = "Revise an owned therapy plan for a currently assigned patient",
            description = "commandId provides idempotency. Scheduling changes require active Profile/CMP, including mixed requests. "
                    + "Stop-only SUSPENDED/CANCELLED requests do not call Profile. No ACTIVE resumption. "
                    + "Returns 201 for new revisions or 200 for an authorized identical replay; 400/401/403/409/503 are safe denials.")
    public Response revise(@PathParam("clinicalPatientId") String patient,
            @PathParam("therapyPlanId") String plan, String body) {
        try {
            var actor = new AssignedPatientRequestSupport(mapper).actor(identity);
            if (identity.hasRole("ADMIN")) throw new SecurityException();
            final CognitoSubject doctor;
            try { doctor = new CognitoSubject(actor.subject()); }
            catch (IllegalArgumentException invalidIdentity) { throw new SecurityException(); }
            var command = TherapyPlanRevisionRestAssembler.toCommand(patient, plan, doctor, body, mapper);
            var result = revisions.handle(command);
            return Response.status(result.idempotentReplay() ? 200 : 201)
                    .entity(TherapyPlanRevisionRestAssembler.toResponse(command, result))
                    .header("Cache-Control", "no-store").build();
        } catch (PatientReadFailure failure) {
            return switch (failure.kind()) {
                case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
                case FORBIDDEN -> error(403, "FORBIDDEN");
                case INVALID_REQUEST -> error(400, "INVALID_REQUEST");
                case UNAVAILABLE -> error(503, "UNAVAILABLE");
            };
        } catch (SecurityException | NoSuchElementException denied) { return error(403, "FORBIDDEN"); }
        catch (TherapyPlanRevisionConflictException | IllegalStateException conflict) { return error(409, "CONFLICT"); }
        catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private static Response error(int status, String kind) {
        return Response.status(status).entity(Map.of("code", "PLAN_REVISION_" + kind))
                .header("Cache-Control", "no-store").build();
    }
}
