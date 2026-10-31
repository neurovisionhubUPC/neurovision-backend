package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.internal.queryservices.GetSessionReportSnapshotService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import java.lang.reflect.Proxy;
import java.util.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSessionReportSnapshotControllerTest {
    final Fixture f = new Fixture();
    SecurityIdentity identity(boolean anonymous, Set<String> roles, String subject, String token, boolean jwt) {
        Object principal = jwt ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p, m, a) -> m.getName().equals("getSubject") ? subject : null) : (java.security.Principal) () -> DOCTOR;
        return (SecurityIdentity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SecurityIdentity.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isAnonymous" -> anonymous;
                    case "hasRole" -> roles.contains(a[0]);
                    case "getPrincipal" -> principal;
                    case "getCredential" -> token == null || token.isBlank() ? null : new AccessTokenCredential(token);
                    default -> null;
                });
    }
    DoctorSessionReportSnapshotController controller(SecurityIdentity identity) {
        return new DoctorSessionReportSnapshotController(f.service(), new GetSessionReportSnapshotService(f), identity, f.mapper,
                new com.neurovision.rehab.clinical.infrastructure.reporting.PdfBoxSessionReportRenderer(f.mapper));
    }
    DoctorSessionReportSnapshotController doctor() { return controller(identity(false, Set.of("DOCTOR"), DOCTOR, TOKEN, true)); }
    Response create(DoctorSessionReportSnapshotController c) { return c.create(PATIENT.toString(), SESSION.toString(), "key", null); }
    Response get(DoctorSessionReportSnapshotController c, UUID id) { return c.get(PATIENT.toString(), SESSION.toString(), id.toString()); }
    JsonNode json(Response response) { return f.mapper.valueToTree(response.getEntity()); }
    void error(Response response, int status, String code) {
        assertEquals(status, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals(Map.of("code", "SESSION_REPORT_SNAPSHOT_" + code), response.getEntity());
        assertEquals(status == 401 ? "Bearer" : null, response.getHeaderString("WWW-Authenticate"));
    }
    @Test void createReplayAndGetExposeSameSafeFrozenEnvelopeWithLocationOnlyForNewCopy() {
        var first = create(doctor()); assertEquals(201, first.getStatus());
        var frozen = json(first);
        assertEquals(Set.of("reportSnapshotId", "createdAt", "report"), fieldNames(frozen));
        var id = UUID.fromString(frozen.path("reportSnapshotId").asText());
        assertEquals(TIME.toString(), frozen.path("createdAt").asText());
        assertEquals("/api/v1/clinical-patients/" + PATIENT + "/therapy-sessions/" + SESSION + "/report-snapshots/" + id,
                first.getLocation().toString()); assertEquals("no-store", first.getHeaderString("Cache-Control"));
        assertFalse(frozen.toString().contains(DOCTOR)); assertFalse(frozen.toString().contains(TOKEN));
        assertFalse(frozen.toString().contains("idempotency"));
        f.failPeer = "names"; f.firstName = "Changed";
        var replay = create(doctor()); assertEquals(200, replay.getStatus()); assertNull(replay.getLocation());
        assertEquals(frozen, json(replay)); assertEquals("no-store", replay.getHeaderString("Cache-Control"));
        var stored = get(doctor(), id); assertEquals(200, stored.getStatus()); assertEquals(frozen, json(stored));
        assertEquals("no-store", stored.getHeaderString("Cache-Control")); assertEquals(3, f.peers);
        var copy = (SessionReportResponse) stored.getEntity(); ((com.fasterxml.jackson.databind.node.ObjectNode) copy.representation()).removeAll(); assertEquals(frozen, json(stored));
    }
    Set<String> fieldNames(JsonNode json) { var names = new HashSet<String>(); json.fieldNames().forEachRemaining(names::add); return names; }
    @Test void authenticationAndPureRoleRulesPrecedeAllLookupsOnBothRoutes() {
        for (var c : List.of(controller(null), controller(identity(true, Set.of(), null, null, true)),
                controller(identity(false, Set.of("DOCTOR"), DOCTOR, null, true)))) {
            error(create(c), 401, "UNAUTHENTICATED"); error(get(c, UUID.randomUUID()), 401, "UNAUTHENTICATED");
        }
        for (var roles : List.of(Set.of("PATIENT"), Set.of("ADMIN"), Set.of("DOCTOR", "PATIENT"), Set.of("DOCTOR", "ADMIN"), Set.<String>of())) {
            var c = controller(identity(false, roles, DOCTOR, TOKEN, true));
            error(create(c), 403, "FORBIDDEN"); error(get(c, UUID.randomUUID()), 403, "FORBIDDEN");
        }
        for (String subject : Arrays.asList(null, "", " padded", "x".repeat(129))) {
            var c = controller(identity(false, Set.of("DOCTOR"), subject, TOKEN, true));
            error(create(c), 403, "FORBIDDEN"); error(get(c, UUID.randomUUID()), 403, "FORBIDDEN");
        }
        var c = controller(identity(false, Set.of("DOCTOR"), DOCTOR, TOKEN, false));
        error(create(c), 403, "FORBIDDEN"); error(get(c, UUID.randomUUID()), 403, "FORBIDDEN"); assertEquals(0, f.reads);
    }
    @Test void validatesAllCanonicalIdsKeyAndAbsentBodyBeforeReads() {
        for (String invalid : Arrays.asList(null, "bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")) {
            error(doctor().create(invalid, SESSION.toString(), "key", null), 400, "INVALID_REQUEST");
            error(doctor().create(PATIENT.toString(), invalid, "key", null), 400, "INVALID_REQUEST");
            error(doctor().get(invalid, SESSION.toString(), UUID.randomUUID().toString()), 400, "INVALID_REQUEST");
            error(doctor().get(PATIENT.toString(), invalid, UUID.randomUUID().toString()), 400, "INVALID_REQUEST");
            error(doctor().get(PATIENT.toString(), SESSION.toString(), invalid), 400, "INVALID_REQUEST");
        }
        for (String key : Arrays.asList(null, "", " ", "x".repeat(129)))
            error(doctor().create(PATIENT.toString(), SESSION.toString(), key, null), 400, "INVALID_REQUEST");
        for (String body : List.of("{}", "[]", "null", "private data"))
            error(doctor().create(PATIENT.toString(), SESSION.toString(), "key", body), 400, "INVALID_REQUEST");
        assertEquals(0, f.reads); assertEquals(0, f.writes); assertEquals(0, f.peers);
    }
    @Test void currentDenialMissingAndUncertaintyMapToSafeApplicationResponses() {
        error(get(doctor(), UUID.randomUUID()), 404, "NOT_FOUND");
        f.allowed = false; error(create(doctor()), 403, "FORBIDDEN"); error(get(doctor(), UUID.randomUUID()), 403, "FORBIDDEN");
        f.allowed = true; f.failRead = true;
        error(create(doctor()), 503, "UNAVAILABLE"); error(get(doctor(), UUID.randomUUID()), 503, "UNAVAILABLE");
        f.failRead = false; f.uncertain = true; error(create(doctor()), 503, "UNAVAILABLE"); assertEquals(1, f.writes);
        f.uncertain = false; var replay = create(doctor()); assertEquals(200, replay.getStatus()); assertEquals(1, f.writes);
        f.allowed = false; error(create(doctor()), 403, "FORBIDDEN");
    }
    @Test void malformedStoredJsonNeverLeaksParserDetailsOrReturnsClientError() {
        for (String json : List.of("invalid", "[]", "null", "{} {}", "{\"x\":1,\"x\":2}")) {
            var id = UUID.randomUUID(); f.copies.put("key", new Snapshot(id, PATIENT, SESSION, TIME, json));
            error(create(doctor()), 503, "UNAVAILABLE"); error(get(doctor(), id), 503, "UNAVAILABLE");
        }
    }
    @Test void routesAreDoctorOnlyAndUseExistingAuthenticatedActorBoundary() throws Exception {
        var type = DoctorSessionReportSnapshotController.class;
        assertEquals("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots",
                type.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertNotNull(type.getAnnotation(jakarta.enterprise.context.RequestScoped.class));
        var create = type.getMethod("create", String.class, String.class, String.class, String.class);
        var get = type.getMethod("get", String.class, String.class, String.class);
        assertNotNull(create.getAnnotation(jakarta.ws.rs.POST.class)); assertNotNull(get.getAnnotation(jakarta.ws.rs.GET.class));
        assertEquals("/{reportSnapshotId}", get.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertEquals("reportSnapshotId", get.getParameters()[2].getAnnotation(jakarta.ws.rs.PathParam.class).value());
        for (var method : List.of(create, get)) assertArrayEquals(new String[]{"DOCTOR"}, method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertEquals("Idempotency-Key", create.getParameters()[2].getAnnotation(jakarta.ws.rs.HeaderParam.class).value());
    }
}
