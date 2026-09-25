package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService.Outcome;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService.Status;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPoint;
import com.neurovision.rehab.analytics.interfaces.rest.transform.GazePlotPageRestAssembler;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GazePlotPageControllerTest {
    @Test
    void endpointRequiresEitherPatientOrDoctorOidcRole() throws Exception {
        var roles = GazePlotPageController.class.getMethod("read", String.class, String.class,
                String.class, String.class).getAnnotation(RolesAllowed.class);
        assertEquals(java.util.Set.of("PATIENT", "DOCTOR"), java.util.Set.of(roles.value()));
    }

    @Test
    void strictlyBoundsPageSizeAndRejectsMalformedCursorShape() {
        assertEquals(QueryGazePlotPageService.DEFAULT_PAGE_SIZE, GazePlotPageController.parsePageSize(null));
        assertEquals(500, GazePlotPageController.parsePageSize("500"));
        assertThrows(IllegalArgumentException.class, () -> GazePlotPageController.parsePageSize("0"));
        assertThrows(IllegalArgumentException.class, () -> GazePlotPageController.parsePageSize("501"));
        assertThrows(IllegalArgumentException.class, () -> GazePlotPageController.parsePageSize("1e2"));
        assertThrows(IllegalArgumentException.class, () -> GazePlotPageController.parsePageSize(" 10"));
    }

    @Test
    void mapsObservedPageAndPublicFailureStatusesWithoutDetails() {
        var success = GazePlotPageController.toResponse(
                new Outcome(Status.OK, page()));
        assertEquals(200, success.getStatus());
        var dto = assertInstanceOf(GazePlotPageRestAssembler.GazePlotPageResponse.class, success.getEntity());
        assertEquals(1, dto.contractVersion());
        assertEquals("OBSERVED_GAZE_SAMPLES", dto.pointSemantics());
        assertEquals(2, dto.projectionRevision());
        assertEquals(1, dto.points().size());

        assertError(Status.INVALID_REQUEST, 400, "GAZE_PLOT_READ_INVALID_REQUEST");
        assertError(Status.UNAUTHENTICATED, 401, "UNAUTHENTICATED");
        assertError(Status.FORBIDDEN, 403, "GAZE_PLOT_READ_FORBIDDEN");
        assertError(Status.NOT_FOUND, 404, "GAZE_PLOT_NOT_FOUND");
        assertError(Status.UNAVAILABLE, 503, "GAZE_PLOT_READ_UNAVAILABLE");
    }

    private static void assertError(Status status, int expectedStatus, String expectedCode) {
        Response response = GazePlotPageController.toResponse(new Outcome(status, null));
        assertEquals(expectedStatus, response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals(Map.of("code", expectedCode), body);
        String serialized = body.toString().toLowerCase();
        assertFalse(serialized.contains("session"));
        assertFalse(serialized.contains("stream"));
        assertFalse(serialized.contains("clinical"));
    }

    private static GazePlotPage page() {
        var identity = new AnalyticsProjectionIdentity(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "baseline-v1");
        var time = Instant.parse("2026-09-24T10:00:00Z");
        var metadata = new GazePlotMetadata(identity, 2, 1, 0, 0, 0, List.of(), 0,
                false, 1, Optional.of(new GazePlotMetadata.TimeRange(time, time)));
        return new GazePlotPage(metadata,
                List.of(new GazePlotPoint(0, 0, time, .25, .75, .9, true)), Optional.empty());
    }
}
