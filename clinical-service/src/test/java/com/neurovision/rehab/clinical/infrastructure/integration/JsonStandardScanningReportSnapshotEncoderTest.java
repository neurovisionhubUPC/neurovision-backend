package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.*;

class JsonStandardScanningReportSnapshotEncoderTest {
    final ObjectMapper mapper=new ObjectMapper();
    final JsonSessionReportSnapshotEncoder codec=new JsonSessionReportSnapshotEncoder(mapper);
    ObjectNode selected() throws Exception {
        var f=new Fixture(); f.standardScanning=true;
        f.scanningSummary=new Summary("standard-scanning-trial-v1",SESSION,new UUID(0,4),1,Origin.SYNTHETIC,1,1,0,0,1,new BigDecimal("12.5"));
        return (ObjectNode)mapper.readTree(codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,Origin.SYNTHETIC)));
    }
    @Test void selectedWhitelistPreservesExactSummaryAndLegacyEncodingOmitsSection() throws Exception {
        var selected=selected(); var section=selected.path("standardScanning");
        var fields=new HashSet<String>(); section.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("calculationVersion","therapySessionId","configurationId","configurationVersion","dataOrigin",
                "reportedTrialCount","detectedTargetCount","omissionCount","interruptedCount","eligibleLocalizationCount","medianLocalizationDurationMs"),fields);
        assertEquals(new BigDecimal("12.5"),section.path("medianLocalizationDurationMs").decimalValue());
        assertEquals(Origin.SYNTHETIC,codec.standardScanningSelection(selected.toString(),SESSION));
        var f=new Fixture(); String legacy=codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION));
        assertFalse(mapper.readTree(legacy).has("standardScanning")); assertNull(codec.standardScanningSelection(legacy,SESSION));
        assertNull(codec.standardScanningSelection("{}",SESSION)); assertFalse(selected.toString().contains(TOKEN)); assertFalse(selected.toString().contains(DOCTOR));
    }
    @Test void confirmedZeroProjectionHasNumericCountsAndAnExplicitNullMedian() throws Exception {
        var f=new Fixture(); f.standardScanning=true;
        f.scanningSummary=new Summary("standard-scanning-trial-v1",SESSION,new UUID(0,4),1,Origin.SYNTHETIC,0,0,0,0,0,null);
        var section=mapper.readTree(codec.encode(f.composer().compose(DOCTOR,TOKEN,PATIENT,SESSION,null,Origin.SYNTHETIC))).path("standardScanning");
        assertTrue(section.path("reportedTrialCount").isIntegralNumber()); assertEquals(0,section.path("reportedTrialCount").asLong());
        assertTrue(section.has("medianLocalizationDurationMs")); assertTrue(section.path("medianLocalizationDurationMs").isNull());
    }
    @Test void malformedAndStaleStoredSectionFailsClosedRatherThanGuessingOmission() throws Exception {
        for (String invalid:List.of("[]","null","{} {}","{\"x\":1,\"x\":2}","{\"standardScanning\":null}","{\"standardScanning\":{}}"))
            assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(invalid,SESSION));
        for (String field:List.of("dataOrigin","calculationVersion","configurationId","therapySessionId","configurationVersion")) {
            var root=selected(); ((ObjectNode)root.path("standardScanning")).put(field,"invalid");
            assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(root.toString(),SESSION));
        }
        var wrongConfig=selected(); ((ObjectNode)wrongConfig.path("configuration")).put("configurationVersion",2);
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(wrongConfig.toString(),SESSION));
        var wrongSession=selected(); ((ObjectNode)wrongSession.path("execution")).put("therapySessionId",new UUID(0,99).toString());
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(wrongSession.toString(),SESSION));
        var coherentOtherSession=selected();
        ((ObjectNode)coherentOtherSession.path("standardScanning")).put("therapySessionId",new UUID(0,99).toString());
        ((ObjectNode)coherentOtherSession.path("execution")).put("therapySessionId",new UUID(0,99).toString());
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(coherentOtherSession.toString(),SESSION));
        var wrongMode=selected().put("mode","SUSTAINED_TARGET_FIXATION");
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(wrongMode.toString(),SESSION));
        var root=selected(); String duplicate=root.toString().replace("\"dataOrigin\":\"SYNTHETIC\"","\"dataOrigin\":\"SYNTHETIC\",\"dataOrigin\":\"PRODUCER_REPORTED\"");
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(duplicate,SESSION));
    }
    @Test void twoSelectedSectionsNeverMasqueradeAsOneOrLegacyOmission() throws Exception {
        var root=selected(); root.set("visualSearch",root.path("standardScanning").deepCopy());
        assertThrows(IllegalStateException.class,()->codec.standardScanningSelection(root.toString(),SESSION));
        assertThrows(IllegalStateException.class,()->codec.visualSearchSelection(root.toString(),SESSION));
    }
}
