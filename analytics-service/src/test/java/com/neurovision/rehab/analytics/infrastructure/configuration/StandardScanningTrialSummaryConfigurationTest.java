package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultReader;
import com.neurovision.rehab.analytics.application.internal.queryservices.StandardScanningTrialSummaryService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class StandardScanningTrialSummaryConfigurationTest {
    @Test void composesTheExistingFinalServiceWithDependentScopeAndNoPortAccessAtConstruction() throws Exception {
        var method = StandardScanningTrialSummaryConfiguration.class.getMethod("standardScanningTrialSummaryService",
                StandardScanningTrialSummaryAuthorizationPort.class,StandardScanningTrialResultReader.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new StandardScanningTrialSummaryConfiguration().standardScanningTrialSummaryService(
                (session,token) -> { fail("No authorization at composition"); return null; },
                (session,origin,limit) -> { fail("No read at composition"); return null; });
        assertEquals(StandardScanningTrialSummaryService.Status.INVALID_TOKEN,service.query(null,null,null).await().indefinitely().status());
    }
}
