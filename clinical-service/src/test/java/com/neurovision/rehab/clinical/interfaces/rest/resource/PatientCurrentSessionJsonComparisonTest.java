package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Plain JUnit: invokes only the static JSON reader, never the opt-in HTTP test methods or Quarkus. */
class PatientCurrentSessionJsonComparisonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode read(String json) throws Exception {
        return PatientVisualSearchAuthorizationHttpIT.currentSessionJson(mapper, json);
    }

    @Test void sameWireValuesCompareEqualDespiteJavaDecimalAndIntegralNodeTypes() throws Exception {
        var parameters = Map.of("threshold", new BigDecimal("0.5"), "maximumTimeMs", 3000);
        for (Object frozen : List.of(parameters, Map.of("parameters", parameters,
                "completionTarget", Map.of("criterion", "REPETITIONS", "target", 5L)))) {
            String wire = mapper.writeValueAsString(frozen);
            // Demonstrate the original asymmetric comparison defect with the same cached Jackson version.
            assertFalse(mapper.valueToTree(frozen).equals(mapper.readTree(wire)));
            JsonNode expected = read(mapper.writeValueAsString(frozen));
            JsonNode actual = read(wire);
            assertEquals(expected, actual);
            assertTrue(actual.findValue("threshold").isBigDecimal(), "Local reader must preserve decimal precision");
        }
    }

    @Test void differingDecimalBeyondDoublePrecisionIsNotAcceptedAsEqual() throws Exception {
        JsonNode expected = read("{\"threshold\":0.5,\"target\":5}");
        JsonNode changed = read("{\"threshold\":0.5000000000000000001,\"target\":5}");
        assertNotEquals(expected, changed, "Decimal normalization must not hide a changed frozen value");
    }

    @Test void wholeTreeEqualityStillRejectsChangedMissingExtraTypedAndReorderedData() throws Exception {
        JsonNode expected = read("{\"threshold\":0.5,\"target\":5,\"steps\":[1,2]}");
        for (String changed : List.of(
                "{\"threshold\":0.6,\"target\":5,\"steps\":[1,2]}",
                "{\"threshold\":0.5,\"target\":6,\"steps\":[1,2]}",
                "{\"threshold\":0.5,\"target\":\"5\",\"steps\":[1,2]}",
                "{\"target\":5,\"steps\":[1,2]}",
                "{\"threshold\":0.5,\"target\":5,\"steps\":[1,2],\"extra\":true}",
                "{\"threshold\":0.5,\"target\":5,\"steps\":[2,1]}")) {
            assertNotEquals(expected, read(changed), "The complete frozen JSON structure must remain strict");
        }
    }

    @Test void localReaderDoesNotChangeSharedMapperConfigurationOrOtherParsing() throws Exception {
        assertFalse(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        String wire = "{\"threshold\":0.5}";
        JsonNode sharedBefore = mapper.readTree(wire);
        assertTrue(sharedBefore.path("threshold").isDouble());
        assertTrue(read(wire).path("threshold").isBigDecimal());
        assertFalse(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        assertEquals(sharedBefore, mapper.readTree(wire));
    }
}
