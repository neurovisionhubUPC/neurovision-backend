package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.neurovision.rehab.clinical.application.internal.queryservices.GetSessionReportSnapshotService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import java.util.*;
import jakarta.ws.rs.core.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSessionReportPdfControllerTest extends DoctorSessionReportSnapshotControllerTest {
    Response pdf(DoctorSessionReportSnapshotController controller, String id) {
        return controller.pdf(PATIENT.toString(), SESSION.toString(), id);
    }
    @Test void downloadsSavedContentWithSafeHeadersWithoutPeersOrWrites() throws Exception {
        f.firstName = "Ñáñez";
        var s = f.create("pdf").snapshot();
        int peers = f.peers, writes = f.writes;
        f.firstName = "Changed"; f.failPeer = "names";
        var result = pdf(doctor(), s.id().toString());
        assertEquals(200, result.getStatus()); assertEquals("application/pdf", result.getMediaType().toString());
        assertEquals("attachment; filename=\"session-report-" + s.id() + ".pdf\"", result.getHeaderString("Content-Disposition"));
        assertEquals("no-store", result.getHeaderString("Cache-Control"));
        try (var doc = Loader.loadPDF((byte[]) result.getEntity())) {
            var text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Ñáñez")); assertFalse(text.contains("Changed"));
        }
        assertEquals(s, f.copies.get("pdf")); assertEquals(writes, f.writes); assertEquals(peers, f.peers);
        f.allowed = false; jsonError(pdf(doctor(), s.id().toString()), 403, "FORBIDDEN");
    }
    void jsonError(Response response, int status, String code) {
        error(response, status, code); assertEquals("application/json", response.getMediaType().toString());
        assertFalse(response.getEntity() instanceof byte[]); assertNull(response.getHeaderString("Content-Disposition"));
    }
    @Test void authorizationAndCanonicalIdsPrecedeReadsAndRendering() {
        for (var c : List.of(controller(null), controller(identity(true, Set.of(), null, null, true)),
                controller(identity(false, Set.of("DOCTOR"), DOCTOR, null, true))))
            jsonError(pdf(c, UUID.randomUUID().toString()), 401, "UNAUTHENTICATED");
        for (var roles : List.of(Set.of("PATIENT"), Set.of("ADMIN"), Set.of("DOCTOR", "ADMIN"), Set.of("DOCTOR", "PATIENT")))
            jsonError(pdf(controller(identity(false, roles, DOCTOR, TOKEN, true)), UUID.randomUUID().toString()), 403, "FORBIDDEN");
        for (String bad : List.of("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")) {
            jsonError(pdf(doctor(), bad), 400, "INVALID_REQUEST");
            jsonError(doctor().pdf(bad, SESSION.toString(), UUID.randomUUID().toString()), 400, "INVALID_REQUEST");
            jsonError(doctor().pdf(PATIENT.toString(), bad, UUID.randomUUID().toString()), 400, "INVALID_REQUEST");
        }
        assertEquals(0, f.reads); assertEquals(0, f.writes); assertEquals(0, f.peers);
    }
    @Test void missingDeniedMalformedAndRendererFailureStayGenericAndJson() {
        jsonError(pdf(doctor(), UUID.randomUUID().toString()), 404, "NOT_FOUND");
        f.allowed = false; jsonError(pdf(doctor(), UUID.randomUUID().toString()), 403, "FORBIDDEN");
        f.allowed = true; f.failRead = true; jsonError(pdf(doctor(), UUID.randomUUID().toString()), 503, "UNAVAILABLE"); f.failRead = false;
        var id = UUID.randomUUID(); f.copies.put("bad", new Snapshot(id, PATIENT, SESSION, TIME, "private invalid JSON"));
        jsonError(pdf(doctor(), id.toString()), 503, "UNAVAILABLE");
        var s = f.create("pdf").snapshot();
        var throwing = new DoctorSessionReportSnapshotController(f.service(), new GetSessionReportSnapshotService(f),
                identity(false, Set.of("DOCTOR"), DOCTOR, TOKEN, true), f.mapper,
                copy -> { throw new IllegalArgumentException("private renderer failure"); },
                new com.neurovision.rehab.clinical.application.internal.queryservices.ListSessionReportSnapshotsService(
                        (doc,patientId,sessionId,offset,limit) -> { throw new AssertionError("PDF must not query the list"); }));
        jsonError(pdf(throwing, s.id().toString()), 503, "UNAVAILABLE");
    }
    @Test void pdfRouteIsDoctorOnlyAndExplicitlyProducesPdf() throws Exception {
        var method = DoctorSessionReportSnapshotController.class.getMethod("pdf", String.class, String.class, String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
        assertEquals("/{reportSnapshotId}/pdf", method.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertArrayEquals(new String[]{"DOCTOR"}, method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertArrayEquals(new String[]{"application/pdf"}, method.getAnnotation(jakarta.ws.rs.Produces.class).value());
    }
}
