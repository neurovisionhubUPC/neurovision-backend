package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsCompensatorySaccadeReportAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID SESSION = new UUID(0,2), CONFIG = new UUID(0,4);
    static ObjectNode body() {
        return JSON.createObjectNode().put("calculationVersion","compensatory-saccade-trial-v1")
                .put("therapySessionId",SESSION.toString()).put("configurationId",CONFIG.toString())
                .put("configurationVersion",1).put("dataOrigin","SYNTHETIC").put("reportedTrialCount",6)
                .put("medianTimeToTargetMs",1000).put("reachedTargetCount",3);
    }
    Summary observe(JsonNode body) {
        return new AnalyticsCompensatorySaccadeReportAdapter((s,o,t) -> {
            assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals("original-token",t); return body;
        }).observe(SESSION,Origin.SYNTHETIC,"original-token");
    }
    @Test void preservesEightFieldsAndConfirmedZeros() {
        var result = observe(body());
        assertEquals(new Summary("compensatory-saccade-trial-v1",SESSION,CONFIG,1,Origin.SYNTHETIC,6,3,new java.math.BigDecimal("1000")),result);
        assertEquals(new Summary("compensatory-saccade-trial-v1",SESSION,CONFIG,1,Origin.SYNTHETIC,0,0,null),
                observe(body().put("reportedTrialCount",0).putNull("medianTimeToTargetMs").put("reachedTargetCount",0)));
    }
    @Test void rejectsWrongIdentityOriginSchemaCountsNumericRangesAndMissingFields() {
        var cases = new ArrayList<JsonNode>(); cases.add(null); cases.add(JSON.nullNode()); cases.add(JSON.createArrayNode());
        for (String field:List.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin",
                "reportedTrialCount","medianTimeToTargetMs","reachedTargetCount")) {
            var value=body(); value.remove(field); cases.add(value);
        }
        cases.add(body().put("extra",1)); cases.add(body().put("calculationVersion","baseline-v1"));
        cases.add(body().put("therapySessionId",new UUID(0,99).toString())); cases.add(body().put("configurationId","1-1-1-1-1"));
        cases.add(body().put("configurationVersion",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("reportedTrialCount",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("configurationVersion",0)); cases.add(body().put("configurationVersion",1.5));
        cases.add(body().put("dataOrigin","PRODUCER_REPORTED")); cases.add(body().put("dataOrigin","synthetic"));
        cases.add(body().put("reportedTrialCount",-1)); cases.add(body().put("reportedTrialCount",1001));
        cases.add(body().put("reachedTargetCount",3.0)); cases.add(body().put("reachedTargetCount",7));
        cases.add(body().put("medianTimeToTargetMs",299)); cases.add(body().put("medianTimeToTargetMs",10001));
        cases.add(body().put("medianTimeToTargetMs","1000")); cases.add(body().putNull("medianTimeToTargetMs"));
        for (var value:cases) { var failure=assertThrows(IllegalStateException.class,()->observe(value)); assertNull(failure.getCause()); }
    }
    @Test void preservesExactHalfMillisecondAndRejectsOtherFractions() {
        assertEquals(new java.math.BigDecimal("1000.5"),
                observe(body().put("medianTimeToTargetMs",new java.math.BigDecimal("1000.5"))).medianTimeToTargetMs());
        assertThrows(IllegalStateException.class, () -> observe(body().put("medianTimeToTargetMs",1000.25)));
    }
    @Test void peerDenialAndNullDoNotBecomeZeroOrAbsence() {
        var adapter=new AnalyticsCompensatorySaccadeReportAdapter((s,o,t)->{throw new SecurityException("private");});
        var failure=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,Origin.SYNTHETIC,"token"));
        assertEquals("Report compensatory saccade unavailable",failure.getMessage()); assertNull(failure.getCause());
    }
}
