package com.neurovision.rehab.clinical.interfaces.events.listeners;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction.TransactionalInvitedClinicalPatientRegistrar;
import com.neurovision.rehab.clinical.interfaces.events.transform.PatientAccountInvitedEventTransformer;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class PatientAccountInvitedKafkaListener {

    private final PatientAccountInvitedEventTransformer transformer;
    private final TransactionalInvitedClinicalPatientRegistrar registrar;

    public PatientAccountInvitedKafkaListener(
            PatientAccountInvitedEventTransformer transformer,
            TransactionalInvitedClinicalPatientRegistrar registrar) {
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
