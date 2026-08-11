package com.neurovision.rehab.profile.interfaces.events.listeners;

import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalInvitedPatientRegistrar;
import com.neurovision.rehab.profile.interfaces.events.transform.PatientAccountInvitedEventTransformer;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class PatientAccountInvitedKafkaListener {

    private final PatientAccountInvitedEventTransformer transformer;
    private final TransactionalInvitedPatientRegistrar registrar;

    public PatientAccountInvitedKafkaListener(
            PatientAccountInvitedEventTransformer transformer,
            TransactionalInvitedPatientRegistrar registrar) {
        this.transformer = Objects.requireNonNull(transformer);
        this.registrar = Objects.requireNonNull(registrar);
    }

    @Incoming("patient-account-invited")
    @Blocking
    @Retry(maxRetries = 3, delay = 1000)
    public void consume(String payload) {
        registrar.handle(transformer.toCommand(payload));
    }
}
