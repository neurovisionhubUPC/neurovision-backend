package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultReader;
import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class VisualSearchTrialSummaryConfigurationTest {
    @Test void composesTheExistingFinalServiceWithDependentScopeAndNoPortAccessAtConstruction() throws Exception {
        var method = VisualSearchTrialSummaryConfiguration.class.getMethod("visualSearchTrialSummaryService",
                VisualSearchTrialSummaryAuthorizationPort.class,VisualSearchTrialResultReader.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new VisualSearchTrialSummaryConfiguration().visualSearchTrialSummaryService(
                (session,token) -> { fail("No authorization at composition"); return null; },
                (session,origin,limit) -> { fail("No read at composition"); return null; });
        assertEquals(VisualSearchTrialSummaryService.Status.INVALID_TOKEN,service.query(null,null,null).await().indefinitely().status());
    }
}
