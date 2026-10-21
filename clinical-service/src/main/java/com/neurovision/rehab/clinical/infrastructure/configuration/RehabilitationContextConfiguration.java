package com.neurovision.rehab.clinical.infrastructure.configuration;

import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.RehabilitationContextStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class RehabilitationContextConfiguration {
    @Produces
    @ApplicationScoped
    RehabilitationContextService rehabilitationContextService(RehabilitationContextStore store) {
        return new RehabilitationContextService(store, Clock.systemUTC());
    }
}
