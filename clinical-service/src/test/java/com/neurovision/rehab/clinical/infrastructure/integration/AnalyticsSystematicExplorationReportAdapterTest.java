package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsSystematicExplorationReportAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID SESSION = new UUID(0,2), CONFIG = new UUID(0,4);
    static ObjectNode body() {
        var root = JSON.createObjectNode().put("calculationVersion","systematic-exploration-trial-v1")
                .put("therapySessionId",SESSION.toString()).put("configurationId",CONFIG.toString())
                .put("configurationVersion",1).put("dataOrigin","SYNTHETIC").put("reportedTrialCount",1);
        root.putArray("traversals").addObject().put("trialId","trial-1").put("outcome","COMPLETED")
                .put("visitedRegionCount",4).put("coveragePercent",100).put("elapsedMs",4000);
        return root;
    }
    Summary observe(JsonNode body) {
        return new AnalyticsSystematicExplorationReportAdapter((s,o,t) -> {
            assertEquals(SESSION,s); assertEquals(Origin.SYNTHETIC,o); assertEquals("original-token",t); return body;
        }).observe(SESSION,Origin.SYNTHETIC,"original-token");
    }
    @Test void preservesSevenFieldsAndFiveRowFieldsAndConfirmedEmpty() {
        var result = observe(body());
        assertEquals(new Summary("systematic-exploration-trial-v1",SESSION,CONFIG,1,Origin.SYNTHETIC,1,
                List.of(new Traversal("trial-1",Outcome.COMPLETED,4,100,4000))),result);
        var empty = body().put("reportedTrialCount",0); empty.putArray("traversals");
        assertTrue(observe(empty).traversals().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.traversals().clear());
        assertFalse(result.toString().contains("trial-1"));
    }
    @Test void rejectsMissingExtraWrongTypesCountsAndBindingsWithoutLeakingCause() {
        var cases = new ArrayList<JsonNode>(); cases.add(null); cases.add(JSON.nullNode()); cases.add(JSON.createArrayNode());
        for (String field : List.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin","reportedTrialCount","traversals")) {
            var value=body(); value.remove(field); cases.add(value);
        }
        cases.add(body().put("extra",1)); cases.add(body().put("calculationVersion","baseline-v1"));
        cases.add(body().put("therapySessionId",new UUID(0,99).toString())); cases.add(body().put("configurationId","1-1-1-1-1"));
        cases.add(body().put("configurationVersion",new java.math.BigInteger("9223372036854775808")));
        cases.add(body().put("configurationVersion",0)); cases.add(body().put("configurationVersion",1.5));
        cases.add(body().put("dataOrigin","PRODUCER_REPORTED")); cases.add(body().put("dataOrigin","synthetic"));
        cases.add(body().put("reportedTrialCount",-1)); cases.add(body().put("reportedTrialCount",1001));
        cases.add(body().put("reportedTrialCount",1.0)); cases.add(body().put("reportedTrialCount",2));
        cases.add(body().putNull("traversals")); cases.add(body().put("traversals","private"));
        for (var value : cases) {
            var error=assertThrows(IllegalStateException.class,()->observe(value)); assertNull(error.getCause());
            assertEquals("Report systematic exploration unavailable",error.getMessage());
        }
    }
    @Test void rejectsMalformedRowsDuplicatesAndUnorderedRowsWithoutSortingOrTruncating() {
        for (String field : List.of("trialId","outcome","visitedRegionCount","coveragePercent","elapsedMs")) {
            var value=body(); ((ObjectNode)value.path("traversals").get(0)).remove(field);
            assertThrows(IllegalStateException.class,()->observe(value));
        }
        for (String field : List.of("visitedRegionCount","coveragePercent","elapsedMs")) {
            var value=body(); ((ObjectNode)value.path("traversals").get(0)).put(field,1.5);
            assertThrows(IllegalStateException.class,()->observe(value));
        }
        for (String field : List.of("visitedRegionCount","coveragePercent")) {
            var value=body(); ((ObjectNode)value.path("traversals").get(0)).put(field,4294967300L);
            assertThrows(IllegalStateException.class,()->observe(value));
        }
        for (var invalid : List.of(JSON.createObjectNode().put("trialId",""),JSON.createObjectNode().put("outcome","SUCCESS"),
                JSON.createObjectNode().put("coveragePercent",99),JSON.createObjectNode().put("elapsedMs",-1),
                JSON.createObjectNode().put("visitedRegionCount",3),JSON.createObjectNode().put("extra",1))) {
            var value=body(); ((ObjectNode)value.path("traversals").get(0)).setAll(invalid);
            assertThrows(IllegalStateException.class,()->observe(value));
        }
        var duplicate=body().put("reportedTrialCount",2); var rows=(ArrayNode)duplicate.get("traversals"); rows.add(rows.get(0).deepCopy());
        assertThrows(IllegalStateException.class,()->observe(duplicate));
        ((ObjectNode)rows.get(0)).put("trialId","z"); ((ObjectNode)rows.get(1)).put("trialId","a");
        assertThrows(IllegalStateException.class,()->observe(duplicate));
        var tooMany=body().put("reportedTrialCount",1001); var values=tooMany.putArray("traversals");
        for(int i=0;i<1001;i++) values.add(body().path("traversals").get(0));
        assertThrows(IllegalStateException.class,()->observe(tooMany));
    }
    @Test void permitsInterruptedAndTimedOutRowsWithoutInventingCompletion() {
        var value=body(); var row=(ObjectNode)value.path("traversals").get(0);
        row.put("outcome","INTERRUPTED").put("visitedRegionCount",0).put("coveragePercent",0).put("elapsedMs",0);
        assertEquals(Outcome.INTERRUPTED,observe(value).traversals().getFirst().outcome());
        row.put("outcome","TIMED_OUT").put("elapsedMs",3000);
        assertEquals(0,observe(value).traversals().getFirst().visitedRegionCount());
    }
    @Test void acceptsTheWholeBoundedThousandRowsWithoutDroppingAny() {
        var value=body().put("reportedTrialCount",1000); var rows=value.putArray("traversals");
        for(int i=0;i<1000;i++) {
            var row=(ObjectNode)body().path("traversals").get(0); row.put("trialId",String.format(java.util.Locale.ROOT,"trial-%04d",i)); rows.add(row);
        }
        var result=observe(value); assertEquals(1000,result.reportedTrialCount()); assertEquals(1000,result.traversals().size());
        assertEquals("trial-0999",result.traversals().getLast().trialId());
    }
    @Test void dependencyFailureDoesNotBecomeEmptyOrExposeMessages() {
        var adapter=new AnalyticsSystematicExplorationReportAdapter((s,o,t)->{ throw new SecurityException("private"); });
        var error=assertThrows(IllegalStateException.class,()->adapter.observe(SESSION,Origin.SYNTHETIC,"token"));
        assertNull(error.getCause()); assertFalse(error.toString().contains("private"));
    }
}
