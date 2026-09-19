package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionService.Outcome;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionService.Status;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionServiceTest;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeatmapProjectionControllerTest {
    @Test
    void mapsSuccessAndEveryPublicFailureStatusDeterministically() {
        var success = HeatmapProjectionController.toResponse(
                new Outcome(Status.OK, QueryHeatmapProjectionServiceTest.projection()));
        assertEquals(200, success.getStatus());
        assertInstanceOf(HeatmapProjectionResponse.class, success.getEntity());

        assertError(Status.UNAUTHENTICATED, 401, "UNAUTHENTICATED");
        assertError(Status.FORBIDDEN, 403, "HEATMAP_READ_FORBIDDEN");
        assertError(Status.NOT_FOUND, 404, "HEATMAP_NOT_FOUND");
        assertError(Status.UNAVAILABLE, 503, "HEATMAP_READ_UNAVAILABLE");
    }

    private static void assertError(Status status, int expectedStatus, String expectedCode) {
        Response response = HeatmapProjectionController.toResponse(new Outcome(status, null));
        assertEquals(expectedStatus, response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals(Map.of("code", expectedCode), body);
        String serialized = body.toString().toLowerCase();
        assertFalse(serialized.contains("session"));
        assertFalse(serialized.contains("stream"));
        assertFalse(serialized.contains("clinical"));
    }
}
