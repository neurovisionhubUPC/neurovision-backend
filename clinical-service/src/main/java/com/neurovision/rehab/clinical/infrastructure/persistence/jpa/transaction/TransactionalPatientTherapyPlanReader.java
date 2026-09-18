package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.queryservices.ListPatientTherapyPlansQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientTherapyPlanView;
import com.neurovision.rehab.clinical.application.ports.in.PatientTherapyPlanQueryUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class TransactionalPatientTherapyPlanReader implements PatientTherapyPlanQueryUseCase {
    private final ListPatientTherapyPlansQueryService delegate;

    public TransactionalPatientTherapyPlanReader(ListPatientTherapyPlansQueryService delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public List<PatientTherapyPlanView> handle(CognitoSubject subject) {
        return delegate.handle(subject);
    }
}
