package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotService;
import com.neurovision.rehab.clinical.application.internal.commandservices.SessionReportSnapshotSelectionConflictException;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin;
import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportPdfRenderer;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.util.*;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DoctorSessionReportSnapshotController {
    private final CreateSessionReportSnapshotService creates;
    private final GetSessionReportSnapshotService reads;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final AssignedPatientRequestSupport support;
    @Context private UriInfo uriInfo;
    private final SessionReportPdfRenderer pdf;
    private final ListSessionReportSnapshotsService lists;

    public DoctorSessionReportSnapshotController(CreateSessionReportSnapshotService creates, GetSessionReportSnapshotService reads,
            SecurityIdentity identity, ObjectMapper mapper, SessionReportPdfRenderer pdf, ListSessionReportSnapshotsService lists) {
        this.creates = Objects.requireNonNull(creates); this.reads = Objects.requireNonNull(reads);
        this.identity = identity; this.mapper = Objects.requireNonNull(mapper);
        this.support = new AssignedPatientRequestSupport(mapper);
        this.pdf = Objects.requireNonNull(pdf);
        this.lists = Objects.requireNonNull(lists);
    }

    @GET @RolesAllowed("DOCTOR")
    @Operation(summary = "List saved report copies for an owned session",
            description = "Pure DOCTOR, current session author and ACTIVE assignment. Metadata only, page0/size20/max100, "
                    + "createdAt DESC then UUID DESC. No peers, status gate, report parsing or rendering. Authorized empty pages are 200; database failures remain 503.")
    public Response list(@PathParam("clinicalPatientId") String patient, @PathParam("therapySessionId") String session,
            @QueryParam("page") @DefaultValue("0") String page, @QueryParam("size") @DefaultValue("20") String size) {
        try {
            var actor = actor();
            var result = lists.list(actor.subject(), uuid(patient), uuid(session), number(page), number(size));
            var root = JsonNodeFactory.instance.objectNode().put("clinicalPatientId", result.clinicalPatientId().toString())
                    .put("therapySessionId", result.therapySessionId().toString()).put("page", result.page())
                    .put("size", result.size()).put("total", result.total());
            var items = root.putArray("snapshots");
            for (var item : result.snapshots()) items.addObject().put("reportSnapshotId", item.reportSnapshotId().toString())
                    .put("createdAt", item.createdAt().toString());
            return Response.ok(new SessionReportResponse(root)).header("Cache-Control", "no-store").build();
        } catch (PatientReadFailure failure) { return failure(failure); }
        catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (SecurityException denied) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private static int number(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
        return Integer.parseInt(raw);
    }

    /** Source-compatible omitted-selection invocation; only the overload below is a REST route. */
    public Response create(String patient, String session, String key, String body) { return create(patient,session,key,null,body); }

    public Response create(String patient, String session, String key, String origin, String body) {
        return create(patient,session,key,origin,null,body);
    }

    public Response create(String patient, String session, String key, String visualOrigin, String scanningOrigin, String body) {
        return create(patient,session,key,visualOrigin,scanningOrigin,null,body);
    }

    public Response create(String patient, String session, String key, String visualOrigin, String scanningOrigin,
            String fixationOrigin, String body) {
        return create(patient,session,key,visualOrigin,scanningOrigin,fixationOrigin,null,body);
    }

    public Response create(String patient, String session, String key, String visualOrigin, String scanningOrigin,
            String fixationOrigin, String saccadeOrigin, String body) {
        return create(patient,session,key,visualOrigin,scanningOrigin,fixationOrigin,saccadeOrigin,null,body);
    }

    @POST @RolesAllowed("DOCTOR")
    @Operation(summary = "Create a manual immutable session report copy",
            description = "Pure DOCTOR, current authorship and ACTIVE assignment required. No request body. "
                    + "Optional sustainedFixationDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen SUSTAINED_TARGET_FIXATION. "
                    + "Optional systematicExplorationDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen SYSTEMATIC_EXPLORATION. "
                    + "Optional compensatorySaccadeDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen COMPENSATORY_SACCADE. "
                    + "Optional standardScanningDataOrigin=SYNTHETIC|PRODUCER_REPORTED selects only frozen STANDARD_SCANNING; selections are mutually exclusive. "
                    + "Optional visualSearchDataOrigin=SYNTHETIC|PRODUCER_REPORTED freezes observed Visual Search metrics. "
                    + "Idempotency-Key plus the same selected/omitted origin replays without enrichment; another origin is 409. "
                    + "A new key makes a new manual copy. Invalid origin/unsupported mode is 400. "
                    + "201 with Location for new copies, 200 for replay. Uncertain writes return generic 503 without automatic retry.")
    public Response create(@PathParam("clinicalPatientId") String patient, @PathParam("therapySessionId") String session,
            @HeaderParam("Idempotency-Key") String key, @QueryParam("visualSearchDataOrigin") String visualSearchDataOrigin,
            @QueryParam("standardScanningDataOrigin") String standardScanningDataOrigin,
            @QueryParam("sustainedFixationDataOrigin") String sustainedFixationDataOrigin,
            @QueryParam("compensatorySaccadeDataOrigin") String compensatorySaccadeDataOrigin,
            @QueryParam("systematicExplorationDataOrigin") String systematicExplorationDataOrigin, String body) {
        try {
            var actor = actor();
            var patientId = uuid(patient); var sessionId = uuid(session);
            if (body != null && !body.isBlank()) throw new IllegalArgumentException();
            var result = creates.create(actor.subject(), actor.token(), patientId, sessionId, key, origin(visualSearchDataOrigin), scanningOrigin(standardScanningDataOrigin), fixationOrigin(sustainedFixationDataOrigin), saccadeOrigin(compensatorySaccadeDataOrigin), explorationOrigin(systematicExplorationDataOrigin));
            var response = Response.status(result.created() ? 201 : 200).entity(response(result.snapshot()));
            if (result.created()) response.location(URI.create("/api/v1/clinical-patients/" + patientId
                    + "/therapy-sessions/" + sessionId + "/report-snapshots/" + result.snapshot().id()));
            return response.header("Cache-Control", "no-store").build();
        } catch (SessionReportSnapshotSelectionConflictException conflict) { return error(409, "SELECTION_CONFLICT"); }
        catch (PatientReadFailure failure) { return failure(failure); }
        catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (SecurityException denied) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    @GET @Path("/{reportSnapshotId}") @RolesAllowed("DOCTOR")
    @Operation(summary = "Read a stored immutable session report copy",
            description = "Uses Clinical only; requires pure DOCTOR, current session authorship and ACTIVE assignment. "
                    + "Returns frozen content, never refreshes identities or Analytics. Authorized missing copy returns 404.")
    public Response get(@PathParam("clinicalPatientId") String patient, @PathParam("therapySessionId") String session,
            @PathParam("reportSnapshotId") String id) {
        try {
            var actor = actor();
            var result = reads.get(actor.subject(), uuid(patient), uuid(session), uuid(id));
            if (result.isEmpty()) return error(404, "NOT_FOUND");
            return Response.ok(response(result.get())).header("Cache-Control", "no-store").build();
        } catch (PatientReadFailure failure) { return failure(failure); }
        catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (SecurityException denied) { return error(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    @GET @Path("/{reportSnapshotId}/pdf") @RolesAllowed("DOCTOR") @Produces("application/pdf")
    @Operation(summary = "Download a stored technical session report as PDF",
            description = "Current pure DOCTOR, authorship and ACTIVE assignment required. Uses the saved copy only; "
                    + "no enrichment, recalculation, writes, graphics or clinical diagnosis. Errors are JSON, never partial PDFs.")
    public Response pdf(@PathParam("clinicalPatientId") String patient, @PathParam("therapySessionId") String session,
            @PathParam("reportSnapshotId") String id) {
        try {
            var actor = actor();
            var result = reads.get(actor.subject(), uuid(patient), uuid(session), uuid(id));
            if (result.isEmpty()) return pdfError(404, "NOT_FOUND");
            final byte[] bytes;
            try {
                bytes = pdf.render(result.get());
                if (bytes == null || bytes.length == 0) throw new IllegalStateException();
            } catch (RuntimeException unavailable) {
                return pdfError(503, "UNAVAILABLE");
            }
            return Response.ok(bytes, "application/pdf").header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename=\"session-report-" + result.get().id() + ".pdf\"").build();
        } catch (PatientReadFailure failure) {
            return Response.fromResponse(failure(failure)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalArgumentException invalid) { return pdfError(400, "INVALID_REQUEST"); }
        catch (SecurityException denied) { return pdfError(403, "FORBIDDEN"); }
        catch (RuntimeException unavailable) { return pdfError(503, "UNAVAILABLE"); }
    }

    private static Response pdfError(int status, String code) {
        return Response.fromResponse(error(status, code)).type(MediaType.APPLICATION_JSON).build();
    }

    private AssignedPatientRequestSupport.Actor actor() {
        var actor = support.actor(identity);
        if (identity.hasRole("ADMIN")) throw new SecurityException();
        return actor;
    }

    private SessionReportResponse response(Snapshot copy) {
        try {
            var reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode report = reader.readTree(copy.reportJson());
            if (report == null || !report.isObject()) throw new IllegalStateException();
            if (report.has("visualSearch") || report.has("standardScanning")) {
                // Preserve the new decimal median without changing legacy baseline number decoding.
                JsonNode exact = reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(copy.reportJson());
                if (report.has("visualSearch")) ((com.fasterxml.jackson.databind.node.ObjectNode)report).set("visualSearch",exact.get("visualSearch"));
                if (report.has("standardScanning")) ((com.fasterxml.jackson.databind.node.ObjectNode)report).set("standardScanning",exact.get("standardScanning"));
            }
            var root = JsonNodeFactory.instance.objectNode().put("reportSnapshotId", copy.id().toString()).put("createdAt", copy.createdAt().toString());
            root.set("report", report);
            return new SessionReportResponse(root);
        } catch (Exception invalidStoredCopy) { throw new IllegalStateException("Session report snapshot unavailable"); }
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
        if (raw == null) throw new IllegalArgumentException();
        var id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) throw new IllegalArgumentException();
        return id;
    }
    private static Response failure(PatientReadFailure failure) {
        return switch (failure.kind()) {
            case UNAUTHENTICATED -> error(401, "UNAUTHENTICATED");
            case FORBIDDEN -> error(403, "FORBIDDEN");
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST");
            case UNAVAILABLE -> error(503, "UNAVAILABLE");
        };
    }
    private static Response error(int status, String code) {
        var response = Response.status(status).entity(Map.of("code", "SESSION_REPORT_SNAPSHOT_" + code)).header("Cache-Control", "no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.build();
    }
}
