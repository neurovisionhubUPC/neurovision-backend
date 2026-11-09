package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;

class JsonSessionReportSnapshotEncoderTest {
    final ObjectMapper mapper=new ObjectMapper();
    final JsonSessionReportSnapshotEncoder codec=new JsonSessionReportSnapshotEncoder(mapper);
    ObjectNode selected() throws Exception {
        var f=new Fixture(); f.visualSearch=true;
        f.visualSummary=new Summary("visual-search-trial-v1",SESSION,new UUID(0,4),1,Origin.SYNTHETIC,1,1,0,0,1,new BigDecimal("12.5"));
        return (ObjectNode)mapper.readTree(codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION,Origin.SYNTHETIC)));
    }
    @Test void selectedWhitelistPreservesExactSummaryAndLegacyEncodingOmitsSection() throws Exception {
        var selected=selected(); var section=selected.path("visualSearch");
        var fields=new HashSet<String>(); section.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin",
                "reportedTrialCount","hitCount","omissionCount","interruptedCount","eligibleSearchCount","medianSearchDurationMs"),fields);
        assertEquals(new BigDecimal("12.5"),section.path("medianSearchDurationMs").decimalValue());
        assertEquals(Origin.SYNTHETIC,codec.visualSearchSelection(selected.toString(),SESSION));
        var f=new Fixture(); String legacy=codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION));
        assertFalse(mapper.readTree(legacy).has("visualSearch")); assertNull(codec.visualSearchSelection(legacy,SESSION));
        assertNull(codec.visualSearchSelection("{}",SESSION)); assertFalse(selected.toString().contains(TOKEN)); assertFalse(selected.toString().contains(DOCTOR));
    }
    @Test void confirmedZeroProjectionHasNumericCountsAndAnExplicitNullMedian() throws Exception {
        var f=new Fixture(); f.visualSearch=true;
        f.visualSummary=new Summary("visual-search-trial-v1",SESSION,new UUID(0,4),1,Origin.SYNTHETIC,0,0,0,0,0,null);
        var section=mapper.readTree(codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION,Origin.SYNTHETIC))).path("visualSearch");
        assertTrue(section.path("reportedTrialCount").isIntegralNumber()); assertEquals(0,section.path("reportedTrialCount").asLong());
        assertTrue(section.has("medianSearchDurationMs")); assertTrue(section.path("medianSearchDurationMs").isNull());
    }
    @Test void malformedAndStaleStoredSectionFailsClosedRatherThanGuessingOmission() throws Exception {
        for (String invalid:List.of("[]","null","{} {}","{\"x\":1,\"x\":2}","{\"visualSearch\":null}","{\"visualSearch\":{}}"))
            assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(invalid,SESSION));
        for (String field:List.of("dataOrigin","calculationVersion","configurationId","therapySessionId","configurationVersion")) {
            var root=selected(); ((ObjectNode)root.path("visualSearch")).put(field,"invalid");
            assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(root.toString(),SESSION));
        }
        var wrongConfig=selected(); ((ObjectNode)wrongConfig.path("configuration")).put("configurationVersion",2);
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(wrongConfig.toString(),SESSION));
        var wrongSession=selected(); ((ObjectNode)wrongSession.path("execution")).put("therapySessionId",new UUID(0,99).toString());
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(wrongSession.toString(),SESSION));
        var coherentOtherSession=selected();
        ((ObjectNode)coherentOtherSession.path("visualSearch")).put("therapySessionId",new UUID(0,99).toString());
        ((ObjectNode)coherentOtherSession.path("execution")).put("therapySessionId",new UUID(0,99).toString());
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(coherentOtherSession.toString(),SESSION));
        var wrongMode=selected().put("mode","SUSTAINED_TARGET_FIXATION");
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(wrongMode.toString(),SESSION));
        var root=selected(); String duplicate=root.toString().replace("\"dataOrigin\":\"SYNTHETIC\"","\"dataOrigin\":\"SYNTHETIC\",\"dataOrigin\":\"PRODUCER_REPORTED\"");
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(duplicate,SESSION));
    }

    ObjectNode exploration() throws Exception {
        var f=new Fixture(); f.exploration=true; f.echoExplorationOrigin=true;
        return (ObjectNode)mapper.readTree(codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION,
                null,null,null,null,com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.SYNTHETIC)));
    }
    @Test void explorationWhitelistAndHistoricalOmissionRemainStable() throws Exception {
        var root=exploration(); var section=root.path("systematicExploration");
        var fields=new HashSet<String>(); section.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin","reportedTrialCount","traversals"),fields);
        fields.clear(); section.path("traversals").get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("trialId","outcome","visitedRegionCount","coveragePercent","elapsedMs"),fields);
        assertEquals("SYNTHETIC",codec.systematicExplorationSelection(root.toString(),SESSION).name());
        assertNull(codec.systematicExplorationSelection("{}",SESSION));
        assertNull(codec.systematicExplorationSelection(selected().toString(),SESSION));
        assertFalse(root.toString().contains(TOKEN)); assertFalse(root.toString().contains(DOCTOR));
    }
    @Test void malformedExplorationCannotMasqueradeAsHistoricalOmissionOrCoexistWithOtherSections() throws Exception {
        for (String invalid:List.of("{\"systematicExploration\":null}","{\"systematicExploration\":{}}"))
            assertThrows(IllegalStateException.class,()->codec.systematicExplorationSelection(invalid,SESSION));
        for(String field:List.of("dataOrigin","calculationVersion","configurationId","therapySessionId","configurationVersion")) {
            var root=exploration(); ((ObjectNode)root.path("systematicExploration")).put(field,"invalid");
            assertThrows(IllegalStateException.class,()->codec.systematicExplorationSelection(root.toString(),SESSION));
        }
        for(String section:List.of("visualSearch","standardScanning","sustainedFixation","compensatorySaccade")) {
            var root=exploration(); root.putNull(section);
            assertThrows(IllegalStateException.class,()->codec.systematicExplorationSelection(root.toString(),SESSION));
            assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(root.toString(),SESSION));
        }
        var wrongMode=exploration(); wrongMode.put("mode","STANDARD_SCANNING");
        assertThrows(IllegalStateException.class,()->codec.systematicExplorationSelection(wrongMode.toString(),SESSION));
        var root=exploration(); ((ObjectNode)root.path("configuration")).put("configurationVersion",2);
        final var wrong=root;
        assertThrows(IllegalStateException.class,()->codec.systematicExplorationSelection(wrong.toString(),SESSION));
    }
}
