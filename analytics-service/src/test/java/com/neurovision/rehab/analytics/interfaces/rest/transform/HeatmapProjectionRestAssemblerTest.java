package com.neurovision.rehab.analytics.interfaces.rest.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionServiceTest;
import org.junit.jupiter.api.Test;

class HeatmapProjectionRestAssemblerTest {
    @Test
    void exposesOnlyTheVersionedPseudonymousHeatmapContract() throws Exception {
        var response = HeatmapProjectionRestAssembler.toResponse(
                QueryHeatmapProjectionServiceTest.projection());

        assertEquals(1, response.contractVersion());
        assertEquals(1, response.sourceSchemaVersion());
        assertEquals("baseline-v1", response.calculationVersion());
        assertEquals(2, response.revision());
        assertEquals(10, response.rows());
        assertEquals(10, response.columns());
        assertEquals(100, response.cellCounts().size());
        assertEquals("normalized-0..1", response.coordinateSpace());
        assertEquals("raw-gaze-sample-count", response.cellWeight());
        assertFalse(response.confidenceFiltered());
        assertEquals(2, response.gazeSampleCount());
        assertNotNull(response.observedTimeRange());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("patient"));
        assertFalse(json.contains("doctor"));
        assertFalse(json.contains("diagnos"));
        assertFalse(json.contains("interpretation"));
    }
}
