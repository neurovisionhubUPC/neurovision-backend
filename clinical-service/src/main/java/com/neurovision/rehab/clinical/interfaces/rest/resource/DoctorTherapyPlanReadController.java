package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.DoctorTherapyPlanQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.interfaces.rest.transform.DoctorTherapyPlanReadAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import java.util.function.Supplier;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-plans")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorTherapyPlanReadController {
    private final DoctorTherapyPlanQueryService queries;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final AssignedPatientRequestSupport support;

    public DoctorTherapyPlanReadController(DoctorTherapyPlanQueryService queries, SecurityIdentity identity, ObjectMapper mapper) {
        this.queries = queries; this.identity = identity; this.mapper = mapper;
        this.support = new AssignedPatientRequestSupport(mapper);
    }

    @GET @RolesAllowed("DOCTOR")
    @Operation(summary = "List the doctor's plans for a currently assigned patient",
            description = "UUID ascending, page 0, size 20 (1–100). Optional exact ACTIVE/SUSPENDED/CANCELLED effective status. No Profile/CMP lookup.")
    public Response list(@PathParam("clinicalPatientId") String patient,
            @QueryParam("page") @DefaultValue("0") String page,
            @QueryParam("size") @DefaultValue("20") String size, @QueryParam("status") String status) {
        return response(() -> DoctorTherapyPlanReadAssembler.page(queries.list(support.actor(identity).subject(),
                uuid(patient), number(page), number(size), status)));
    }

    @GET @Path("/{therapyPlanId}") @RolesAllowed("DOCTOR")
    @Operation(summary = "Read an owned plan and its currently effective prescribed configurations",
            description = "Current ACTIVE assignment and plan ownership required. Not an admission guarantee or configuration history.")
    public Response detail(@PathParam("clinicalPatientId") String patient, @PathParam("therapyPlanId") String plan) {
        return response(() -> DoctorTherapyPlanReadAssembler.detail(
                queries.detail(support.actor(identity).subject(), uuid(patient), uuid(plan)), mapper));
    }

    private static UUID uuid(String raw) {
        if (raw == null) throw new IllegalArgumentException();
        var id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) throw new IllegalArgumentException();
        return id;
    }
    private static int number(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
        return Integer.parseInt(raw);
    }
    private static Response response(Supplier<Object> action) {
        try { return Response.ok(action.get()).header("Cache-Control", "no-store").build(); }
        catch (PatientReadFailure failure) {
            return switch (failure.kind()) {
                case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
                case FORBIDDEN -> error(403, "FORBIDDEN");
                case INVALID_REQUEST -> error(400, "INVALID_REQUEST");
                case UNAVAILABLE -> error(503, "UNAVAILABLE");
            };
        } catch (SecurityException denied) { return error(403, "FORBIDDEN"); }
        catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }
    private static Response error(int status, String kind) {
        return Response.status(status).entity(Map.of("code", "PLAN_READ_" + kind)).header("Cache-Control", "no-store").build();
    }
}
