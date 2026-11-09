package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsStandardScanningReportAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID SESSION = new UUID(0,2), CONFIG = new UUID(0,4);
    static ObjectNode body() {
        return JSON.createObjectNode().put("calculationVersion","standard-scanning-trial-v1")
                .put("therapySessionId",SESSION.toString()).put("configurationId",CONFIG.toString())
                .put("configurationVersion",1).put("dataOrigin","SYNTHETIC").put("reportedTrialCount",6)
                .put("detectedTargetCount",3).put("omissionCount",2).put("interruptedCount",1).put("eligibleLocalizationCount",3)
                .put("medianLocalizationDurationMs",new BigDecimal("12.5"));
    }
    Summary observe(JsonNode body) {
        return new AnalyticsStandardScanningReportAdapter((s,o,t) -> {
            assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals("original-token",t); return body;
        }).observe(SESSION,Origin.SYNTHETIC,"original-token");
    }
    @Test void preservesAllElevenFieldsIncludingFractionalMedianAndConfirmedZero() {
        var result = observe(body()); assertEquals(new BigDecimal("12.5"),result.medianLocalizationDurationMs());
        assertEquals(6,result.reportedTrialCount()); assertEquals(3,result.detectedTargetCount()); assertEquals(2,result.omissionCount());
        assertEquals(1,result.interruptedCount()); assertEquals(3,result.eligibleLocalizationCount()); assertEquals(CONFIG,result.configurationId());
        var zero=body(); for (String field:List.of("reportedTrialCount","detectedTargetCount","omissionCount","interruptedCount","eligibleLocalizationCount")) zero.put(field,0);
        zero.putNull("medianLocalizationDurationMs"); assertEquals(0,observe(zero).reportedTrialCount()); assertNull(observe(zero).medianLocalizationDurationMs());
    }
    @Test void rejectsWrongIdentityOriginSchemaCountsNumericRangesAndMissingFields() {
        var cases = new ArrayList<JsonNode>(); cases.add(null); cases.add(JSON.nullNode()); cases.add(JSON.createArrayNode());
        for (String field:List.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin",
                "reportedTrialCount","detectedTargetCount","omissionCount","interruptedCount","eligibleLocalizationCount","medianLocalizationDurationMs")) {
            var value=body(); value.remove(field); cases.add(value);
        }
        cases.add(body().put("extra",1)); cases.add(body().put("calculationVersion","baseline-v1"));
        cases.add(body().put("therapySessionId",new UUID(0,99).toString())); cases.add(body().put("configurationId","1-1-1-1-1"));
        cases.add(body().put("configurationVersion",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("reportedTrialCount",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("configurationVersion",0)); cases.add(body().put("configurationVersion",1.5));
        cases.add(body().put("dataOrigin","PRODUCER_REPORTED")); cases.add(body().put("dataOrigin","synthetic"));
        cases.add(body().put("reportedTrialCount",-1)); cases.add(body().put("reportedTrialCount",7));
        cases.add(body().put("detectedTargetCount",3.0)); cases.add(body().put("eligibleLocalizationCount",2));
        cases.add(body().put("detectedTargetCount",Long.MAX_VALUE)); cases.add(body().put("reportedTrialCount","6"));
        cases.add(body().putNull("medianLocalizationDurationMs")); cases.add(body().put("medianLocalizationDurationMs",-1));
        cases.add(body().put("medianLocalizationDurationMs","12.5")); cases.add(body().put("medianLocalizationDurationMs",12.25));
        cases.add(body().put("medianLocalizationDurationMs",new BigDecimal("9223372036854775808")));
        for (var value:cases) { var failure=assertThrows(IllegalStateException.class,()->observe(value)); assertNull(failure.getCause()); }
    }
    @Test void peerDenialAndNullDoNotBecomeZeroOrAbsence() {
        var adapter=new AnalyticsStandardScanningReportAdapter((s,o,t)->{throw new SecurityException("private");});
        var failure=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,Origin.SYNTHETIC,"token"));
        assertEquals("Report standard scanning unavailable",failure.getMessage()); assertNull(failure.getCause());
    }
}
