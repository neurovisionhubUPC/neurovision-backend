package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultReader;
import com.neurovision.rehab.analytics.application.internal.queryservices.CompensatorySaccadeTrialSummaryService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class CompensatorySaccadeTrialSummaryConfigurationTest {
    @Test void composesTheExistingFinalServiceWithDependentScopeAndNoPortAccessAtConstruction() throws Exception {
        var method = CompensatorySaccadeTrialSummaryConfiguration.class.getMethod("compensatorySaccadeTrialSummaryService",
                CompensatorySaccadeTrialSummaryAuthorizationPort.class,CompensatorySaccadeTrialResultReader.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new CompensatorySaccadeTrialSummaryConfiguration().compensatorySaccadeTrialSummaryService(
                (session,token) -> { fail("No authorization at composition"); return null; },
                (session,origin,limit) -> { fail("No read at composition"); return null; });
        assertEquals(CompensatorySaccadeTrialSummaryService.Status.INVALID_TOKEN,service.query(null,null,null).await().indefinitely().status());
    }
}
