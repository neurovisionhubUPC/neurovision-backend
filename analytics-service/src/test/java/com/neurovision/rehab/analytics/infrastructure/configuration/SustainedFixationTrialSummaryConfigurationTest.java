package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialSummaryConfigurationTest {
    @Test void composesTheExistingFinalServiceWithDependentScopeAndNoPortAccessAtConstruction() throws Exception {
        var method = SustainedFixationTrialSummaryConfiguration.class.getMethod("sustainedFixationTrialSummaryService",
                SustainedFixationTrialSummaryAuthorizationPort.class,SustainedFixationTrialResultReader.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new SustainedFixationTrialSummaryConfiguration().sustainedFixationTrialSummaryService(
                (session,token) -> { fail("No authorization at composition"); return null; },
                (session,origin,limit) -> { fail("No read at composition"); return null; });
        assertEquals(SustainedFixationTrialSummaryService.Status.INVALID_TOKEN,service.query(null,null,null).await().indefinitely().status());
    }
}
