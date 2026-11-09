package com.neurovision.rehab.analytics.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

class StandardScanningTrialReceptionConfigurationTest {
    @Test void producesTheExistingFinalServiceWithoutAProxyOrNewBusinessLogic() throws Exception {
        var method = StandardScanningTrialReceptionConfiguration.class.getMethod("receiveStandardScanningTrialResultService",
                StandardScanningTrialAuthorizationPort.class, StandardScanningTrialResultStore.class);
        assertNotNull(method.getAnnotation(Produces.class)); assertNotNull(method.getAnnotation(Dependent.class));
        var service = new StandardScanningTrialReceptionConfiguration().receiveStandardScanningTrialResultService(
                (id,token) -> {fail("Missing input must not authorize"); return null;}, new StandardScanningTrialResultStore() {
                    public io.smallrye.mutiny.Uni<java.util.Optional<Stored>> find(java.util.UUID id,String trial) {fail();return null;}
                    public io.smallrye.mutiny.Uni<WriteResult> insertIfAbsent(Stored value) {fail();return null;}
                });
        assertEquals(com.neurovision.rehab.analytics.application.internal.commandservices.ReceiveStandardScanningTrialResultService.Status.INVALID_TOKEN,
                service.receive(null,null).await().indefinitely());
    }
}
