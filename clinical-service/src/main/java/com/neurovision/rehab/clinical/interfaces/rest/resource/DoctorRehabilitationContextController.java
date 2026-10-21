package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextFailure;
import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextService;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientReadFailure;
import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.security.identity.SecurityIdentity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.openapi.annotations.Operation;
import static com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext.Change.*;

@Path("/api/v1/clinical-patients/{clinicalPatientId}/rehabilitation-context")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@RolesAllowed("DOCTOR")
public class DoctorRehabilitationContextController {
    private static final Set<String> FIELDS = Set.of("expectedVersion", "affectedHemifield",
            "previousDiagnosis", "sequelae", "observations");
    private final RehabilitationContextService service;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final AssignedPatientRequestSupport support;

    public DoctorRehabilitationContextController(RehabilitationContextService service,
            SecurityIdentity identity, ObjectMapper mapper) {
        this.service = service;
        this.identity = identity;
        this.mapper = mapper;
        this.support = new AssignedPatientRequestSupport(mapper);
    }

    @GET
    @Operation(summary = "Read assigned patient rehabilitation context",
            description = "Assigned DOCTOR only. Manual optional fields, not an automated diagnosis. No Profile lookup.")
    public Response read(@PathParam("clinicalPatientId") String id) {
        return respond(() -> {
            var actor = support.actor(identity);
            return view(service.read(canonicalId(id), actor.subject()));
        });
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update assigned patient rehabilitation context",
            description = "Partial JSON update with required expectedVersion and at least one context field. "
                    + "Omitted fields remain unchanged; null clears, including sequelae to []. "
                    + "LEFT/RIGHT hemifield; text limits 500/1000 total/2000 Unicode code points. "
                    + "Stale version returns 409; unknown or unassigned returns 403. No eligibility or status change.")
    public Response patch(@PathParam("clinicalPatientId") String id, String body) {
        return respond(() -> {
            var actor = support.actor(identity);
            var patient = canonicalId(id);
            var request = parse(body);
            return view(service.patch(patient, actor.subject(), request.expectedVersion(), request.patch()));
        });
    }

    private Request parse(String body) {
        try {
            if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException();
            JsonNode json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (json == null || !json.isObject() || json.size() < 2) throw new IllegalArgumentException();
            var keys = json.fieldNames();
            while (keys.hasNext()) if (!FIELDS.contains(keys.next())) throw new IllegalArgumentException();
            var version = json.path("expectedVersion");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) throw new IllegalArgumentException();
            var side = !json.has("affectedHemifield") ? RehabilitationContext.Change.<RehabilitationContext.Hemifield>absent()
                    : set(json.get("affectedHemifield").isNull() ? null
                    : RehabilitationContext.Hemifield.valueOf(text(json.get("affectedHemifield"))));
            RehabilitationContext.Change<List<String>> sequelae = absent();
            if (json.has("sequelae")) {
                var value = json.get("sequelae");
                if (value.isNull()) sequelae = set(null);
                else {
                    if (!value.isArray() || value.size() > 1000) throw new IllegalArgumentException();
                    var entries = new ArrayList<String>();
                    for (var entry : value) {
                        if (entry.isNull()) throw new IllegalArgumentException();
                        entries.add(text(entry));
                    }
                    sequelae = set(entries);
                }
            }
            var patch = new RehabilitationContext.Patch(side,
                    json.has("previousDiagnosis") ? set(text(json.get("previousDiagnosis"))) : absent(),
                    sequelae, json.has("observations") ? set(text(json.get("observations"))) : absent());
            return new Request(version.longValue(), patch);
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid rehabilitation context request"); }
    }

    private static String text(JsonNode value) {
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static UUID canonicalId(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private Map<String, Object> view(RehabilitationContextStore.Snapshot snapshot) {
        var context = snapshot.context();
        var result = new LinkedHashMap<String, Object>();
        result.put("clinicalPatientId", snapshot.clinicalPatientId());
        result.put("version", snapshot.version());
        result.put("updatedAt", snapshot.updatedAt().toString());
        result.put("affectedHemifield", context.affectedHemifield());
        result.put("previousDiagnosis", context.previousDiagnosis());
        result.put("sequelae", context.sequelae());
        result.put("observations", context.observations());
        return result;
    }

    private Response respond(Supplier<Object> action) {
        try { return Response.ok(action.get()).header("Cache-Control", "no-store").build(); }
        catch (RehabilitationContextFailure failure) {
            int status = switch (failure.kind()) {
                case INVALID_REQUEST -> 400; case FORBIDDEN -> 403; case CONFLICT -> 409; case UNAVAILABLE -> 503;
            };
            return error(status, failure.kind().name());
        } catch (PatientReadFailure failure) {
            return error(switch (failure.kind()) {
                case INVALID_REQUEST -> 400; case UNAUTHENTICATED -> 401; case FORBIDDEN -> 403; case UNAVAILABLE -> 503;
            }, failure.kind().name());
        } catch (IllegalArgumentException invalid) { return error(400, "INVALID_REQUEST"); }
        catch (RuntimeException unavailable) { return error(503, "UNAVAILABLE"); }
    }

    private Response error(int status, String kind) {
        return Response.status(status).entity(Map.of("code", "REHABILITATION_CONTEXT_" + kind))
                .header("Cache-Control", "no-store").build();
    }

    private record Request(long expectedVersion, RehabilitationContext.Patch patch) {}
}
