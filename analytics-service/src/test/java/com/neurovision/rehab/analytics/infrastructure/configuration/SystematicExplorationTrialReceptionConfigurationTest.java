package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialReceptionConfigurationTest {
    @Test void producesTheExistingFinalServiceWithoutAProxyOrNewBusinessLogic() throws Exception {
        var method = SystematicExplorationTrialReceptionConfiguration.class.getMethod("receiveSystematicExplorationTrialResultService",
                SystematicExplorationTrialAuthorizationPort.class, SystematicExplorationTrialResultStore.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new SystematicExplorationTrialReceptionConfiguration().receiveSystematicExplorationTrialResultService(
                (id,token) -> {fail("Missing input must not authorize"); return null;}, new SystematicExplorationTrialResultStore() {
                    public io.smallrye.mutiny.Uni<java.util.Optional<Stored>> find(java.util.UUID id,String trial) {fail();return null;}
                    public io.smallrye.mutiny.Uni<WriteResult> insertIfAbsent(Stored value) {fail();return null;}
                });
        assertEquals(com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSystematicExplorationTrialResultService.Status.INVALID_TOKEN,
                service.receive(null,null).await().indefinitely());
    }
}
