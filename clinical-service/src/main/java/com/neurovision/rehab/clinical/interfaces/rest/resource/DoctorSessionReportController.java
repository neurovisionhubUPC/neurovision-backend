package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionReportRestAssembler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-preview")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorSessionReportController {
    private final ComposeSessionReportService reports;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final AssignedPatientRequestSupport support;
    @Context private UriInfo uriInfo;

    public DoctorSessionReportController(ComposeSessionReportService reports, SecurityIdentity identity, ObjectMapper mapper) {
        this.reports = Objects.requireNonNull(reports);
        this.identity = identity;
        this.mapper = Objects.requireNonNull(mapper);
        this.support = new AssignedPatientRequestSupport(mapper);
    }

    /** Source-compatible omitted-selection invocation; only the overload below is a REST route. */
    public Response preview(String patient, String session) { return preview(patient,session,null); }

    public Response preview(String patient, String session, String origin) { return preview(patient,session,origin,null); }

    public Response preview(String patient, String session, String visualOrigin, String scanningOrigin) {
        return preview(patient,session,visualOrigin,scanningOrigin,null);
    }

    public Response preview(String patient, String session, String visualOrigin, String scanningOrigin, String fixationOrigin) {
        return preview(patient,session,visualOrigin,scanningOrigin,fixationOrigin,null);
    }

    public Response preview(String patient, String session, String visualOrigin, String scanningOrigin, String fixationOrigin, String saccadeOrigin) {
        return preview(patient,session,visualOrigin,scanningOrigin,fixationOrigin,saccadeOrigin,null);
    }

    @GET @RolesAllowed("DOCTOR")
    @Operation(summary = "Preview an owned completed session report",
            description = "Requires DOCTOR without PATIENT/ADMIN, authorship and current ACTIVE assignment. "
                    + "Read-only bounded evidence and current identity availability; no PDF, stored report or clinical interpretation. "
                    + "Optional sustainedFixationDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen SUSTAINED_TARGET_FIXATION. "
                    + "Optional systematicExplorationDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen SYSTEMATIC_EXPLORATION. "
                    + "Optional compensatorySaccadeDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen COMPENSATORY_SACCADE. "
                    + "Optional standardScanningDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen STANDARD_SCANNING; selections are mutually exclusive. "
                    + "Optional visualSearchDataOrigin=SYNTHETIC|PRODUCER_REPORTED requests observed Visual Search metrics only for "
                    + "TARGET_SEARCH_WITH_DISTRACTORS; omission preserves the existing report. Invalid origin/mode is 400. "
                    + "Incomplete or unavailable composition returns generic 503, never analytical absence.")
    public Response preview(@PathParam("clinicalPatientId") String patient, @PathParam("therapySessionId") String session,
            @QueryParam("visualSearchDataOrigin") String visualSearchDataOrigin,
            @QueryParam("standardScanningDataOrigin") String standardScanningDataOrigin,
            @QueryParam("sustainedFixationDataOrigin") String sustainedFixationDataOrigin,
            @QueryParam("compensatorySaccadeDataOrigin") String compensatorySaccadeDataOrigin,
            @QueryParam("systematicExplorationDataOrigin") String systematicExplorationDataOrigin) {
        try {
            var actor = support.actor(identity);
            if (identity.hasRole("ADMIN")) throw new SecurityException();
            var result = reports.compose(actor.subject(), actor.token(), uuid(patient), uuid(session), origin(visualSearchDataOrigin), scanningOrigin(standardScanningDataOrigin), fixationOrigin(sustainedFixationDataOrigin), saccadeOrigin(compensatorySaccadeDataOrigin), explorationOrigin(systematicExplorationDataOrigin));
            return Response.ok(SessionReportRestAssembler.toResponse(result, mapper))
                    .header("Cache-Control", "no-store").build();
        } catch (PatientReadFailure failure) {
            return switch (failure.kind()) {
                case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
                case FORBIDDEN -> error(403, "FORBIDDEN");
                case INVALID_REQUEST -> error(400, "INVALID_REQUEST");
                case UNAVAILABLE -> error(503, "UNAVAILABLE");
            };
        } catch (SecurityException denied) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private Origin origin(String raw) {
        if (raw == null) {
            // Quarkus REST can bind an explicitly empty query value to null; only an absent key means omission.
            if (uriInfo != null && uriInfo.getQueryParameters().containsKey("visualSearchDataOrigin"))
                throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
            return null;
        }
        try { return Origin.valueOf(raw); }
        catch (IllegalArgumentException invalid) { throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST); }
    }

    private com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin scanningOrigin(String raw) {
        if (raw == null) {
            // Quarkus REST can bind an explicitly empty query value to null; only an absent key means omission.
            if (uriInfo != null && uriInfo.getQueryParameters().containsKey("standardScanningDataOrigin"))
                throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
            return null;
        }
        try { return com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.valueOf(raw); }
        catch (IllegalArgumentException invalid) { throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST); }
    }

    private com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin fixationOrigin(String raw) {
        if (uriInfo != null) {
            var query = uriInfo.getQueryParameters();
            if (query.containsKey("sustainedFixationDataOrigin")) {
                var values = query.get("sustainedFixationDataOrigin");
                if (raw == null || values == null || values.size() != 1 || !raw.equals(values.getFirst()))
                    throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
            }
        }
        if (raw == null) return null;
        try { return com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.valueOf(raw); }
        catch (IllegalArgumentException invalid) { throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST); }
    }

    private com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin saccadeOrigin(String raw) {
        if (uriInfo != null) {
            var query = uriInfo.getQueryParameters();
            if (query.containsKey("compensatorySaccadeDataOrigin")) {
                var values = query.get("compensatorySaccadeDataOrigin");
                if (raw == null || values == null || values.size() != 1 || !raw.equals(values.getFirst()))
                    throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
            }
        }
        if (raw == null) return null;
        try { return com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.valueOf(raw); }
        catch (IllegalArgumentException invalid) { throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST); }
    }

    private com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin explorationOrigin(String raw) {
        if (uriInfo != null) {
            var query = uriInfo.getQueryParameters();
            if (query.containsKey("systematicExplorationDataOrigin")) {
                var values = query.get("systematicExplorationDataOrigin");
                if (raw == null || values == null || values.size() != 1 || !raw.equals(values.getFirst()))
                    throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
            }
        }
        if (raw == null) return null;
        try { return com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.valueOf(raw); }
        catch (IllegalArgumentException invalid) { throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST); }
    }

    private static UUID uuid(String raw) {
        try {
            if (raw == null) throw new IllegalArgumentException();
            var id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new PatientReadFailure(PatientReadFailure.Kind.INVALID_REQUEST);
        }
    }

    private static Response error(int status, String kind) {
        var response = Response.status(status).entity(Map.of("code", "SESSION_REPORT_" + kind))
                .header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
}
