package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsSustainedFixationReportAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID SESSION = new UUID(0,2), CONFIG = new UUID(0,4);
    static ObjectNode body() {
        return JSON.createObjectNode().put("calculationVersion","sustained-fixation-trial-v1")
                .put("therapySessionId",SESSION.toString()).put("configurationId",CONFIG.toString())
                .put("configurationVersion",1).put("dataOrigin","SYNTHETIC").put("reportedTrialCount",6)
                .put("totalInsideTargetMs",3600).put("completedTargetCount",3);
    }
    Summary observe(JsonNode body) {
        return new AnalyticsSustainedFixationReportAdapter((s,o,t) -> {
            assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals("original-token",t); return body;
        }).observe(SESSION,Origin.SYNTHETIC,"original-token");
    }
    @Test void preservesEightFieldsAndConfirmedZeros() {
        var result = observe(body());
        assertEquals(new Summary("sustained-fixation-trial-v1",SESSION,CONFIG,1,Origin.SYNTHETIC,6,3600,3),result);
        assertEquals(new Summary("sustained-fixation-trial-v1",SESSION,CONFIG,1,Origin.SYNTHETIC,0,0,0),
                observe(body().put("reportedTrialCount",0).put("totalInsideTargetMs",0).put("completedTargetCount",0)));
    }
    @Test void rejectsWrongIdentityOriginSchemaCountsNumericRangesAndMissingFields() {
        var cases = new ArrayList<JsonNode>(); cases.add(null); cases.add(JSON.nullNode()); cases.add(JSON.createArrayNode());
        for (String field:List.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin",
                "reportedTrialCount","totalInsideTargetMs","completedTargetCount")) {
            var value=body(); value.remove(field); cases.add(value);
        }
        cases.add(body().put("extra",1)); cases.add(body().put("calculationVersion","baseline-v1"));
        cases.add(body().put("therapySessionId",new UUID(0,99).toString())); cases.add(body().put("configurationId","1-1-1-1-1"));
        cases.add(body().put("configurationVersion",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("reportedTrialCount",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("configurationVersion",0)); cases.add(body().put("configurationVersion",1.5));
        cases.add(body().put("dataOrigin","PRODUCER_REPORTED")); cases.add(body().put("dataOrigin","synthetic"));
        cases.add(body().put("reportedTrialCount",-1)); cases.add(body().put("reportedTrialCount",1001));
        cases.add(body().put("completedTargetCount",3.0)); cases.add(body().put("completedTargetCount",7));
        cases.add(body().put("totalInsideTargetMs",899)); cases.add(body().put("totalInsideTargetMs",30001));
        cases.add(body().put("totalInsideTargetMs","3600")); cases.add(body().putNull("totalInsideTargetMs"));
        for (var value:cases) { var failure=assertThrows(IllegalStateException.class,()->observe(value)); assertNull(failure.getCause()); }
    }
    @Test void peerDenialAndNullDoNotBecomeZeroOrAbsence() {
        var adapter=new AnalyticsSustainedFixationReportAdapter((s,o,t)->{throw new SecurityException("private");});
        var failure=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,Origin.SYNTHETIC,"token"));
        assertEquals("Report sustained fixation unavailable",failure.getMessage()); assertNull(failure.getCause());
    }
}
