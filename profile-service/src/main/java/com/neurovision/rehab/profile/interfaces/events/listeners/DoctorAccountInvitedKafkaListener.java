package com.neurovision.rehab.profile.interfaces.events.listeners;

import com.neurovision.rehab.profile.infrastructure.persistence.jpa.transaction.TransactionalInvitedDoctorRegistrar;
import com.neurovision.rehab.profile.interfaces.events.transform.DoctorAccountInvitedEventTransformer;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class DoctorAccountInvitedKafkaListener {

    private final DoctorAccountInvitedEventTransformer transformer;
    private final TransactionalInvitedDoctorRegistrar registrar;

    public DoctorAccountInvitedKafkaListener(
            DoctorAccountInvitedEventTransformer transformer,
            TransactionalInvitedDoctorRegistrar registrar) {
        this.transformer = Objects.requireNonNull(transformer);
        this.registrar = Objects.requireNonNull(registrar);
    }

    @Incoming("doctor-account-invited")
    @Blocking
    @Retry(maxRetries = 3, delay = 1000)
    public void consume(String payload) {
        registrar.handle(transformer.toCommand(payload));
    }
}
