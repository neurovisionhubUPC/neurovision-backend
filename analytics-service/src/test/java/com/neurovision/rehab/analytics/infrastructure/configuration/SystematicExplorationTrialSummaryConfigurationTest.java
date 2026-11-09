package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultReader;
import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialSummaryConfigurationTest {
    @Test void composesTheExistingFinalServiceWithDependentScopeAndNoPortAccessAtConstruction() throws Exception {
        var method = SystematicExplorationTrialSummaryConfiguration.class.getMethod("systematicExplorationTrialSummaryService",
                SystematicExplorationTrialSummaryAuthorizationPort.class,SystematicExplorationTrialResultReader.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new SystematicExplorationTrialSummaryConfiguration().systematicExplorationTrialSummaryService(
                (session,token) -> { fail("No authorization at composition"); return null; },
                (session,origin,limit) -> { fail("No read at composition"); return null; });
        assertEquals(SystematicExplorationTrialSummaryService.Status.INVALID_TOKEN,service.query(null,null,null).await().indefinitely().status());
    }
}
