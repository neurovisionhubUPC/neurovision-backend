package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.application.ports.out.persistence.PatientNameReadPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped
public class JpaPatientNameReadAdapter implements PatientNameReadPort {
    private final EntityManager em;
    public JpaPatientNameReadAdapter(EntityManager em) { this.em=Objects.requireNonNull(em); }
    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String,Entry> names(Set<String> subjects) {
        if(subjects==null || subjects.isEmpty() || subjects.size()>100) throw new IllegalArgumentException("Invalid name batch");
        var rows=em.createQuery("select p.cognitoSubject,p.profileType,p.status,p.firstName,p.lastName from UserProfileEntity p where p.cognitoSubject in :subjects",Object[].class)
                .setParameter("subjects",subjects).setMaxResults(100).getResultList();
        var result=new HashMap<String,Entry>();
        for(var row:rows) if(result.putIfAbsent((String)row[0],new Entry((String)row[1],(String)row[2],(String)row[3],(String)row[4]))!=null) throw new IllegalStateException("Ambiguous profile binding");
        return result;
    }
}
