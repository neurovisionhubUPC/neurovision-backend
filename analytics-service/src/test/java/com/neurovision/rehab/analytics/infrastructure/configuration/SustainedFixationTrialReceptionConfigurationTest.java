package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialReceptionConfigurationTest {
    @Test void producesTheExistingFinalServiceWithoutAProxyOrNewBusinessLogic() throws Exception {
        var method = SustainedFixationTrialReceptionConfiguration.class.getMethod("receiveSustainedFixationTrialResultService",
                SustainedFixationTrialAuthorizationPort.class, SustainedFixationTrialResultStore.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new SustainedFixationTrialReceptionConfiguration().receiveSustainedFixationTrialResultService(
                (id,token) -> {fail("Missing input must not authorize"); return null;}, new SustainedFixationTrialResultStore() {
                    public io.smallrye.mutiny.Uni<java.util.Optional<Stored>> find(java.util.UUID id,String trial) {fail();return null;}
                    public io.smallrye.mutiny.Uni<WriteResult> insertIfAbsent(Stored value) {fail();return null;}
                });
        assertEquals(com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveSustainedFixationTrialResultService.Status.INVALID_TOKEN,
                service.receive(null,null).await().indefinitely());
    }
}
