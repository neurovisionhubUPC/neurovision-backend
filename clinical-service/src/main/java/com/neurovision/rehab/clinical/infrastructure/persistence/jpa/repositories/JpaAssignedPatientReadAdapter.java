package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.AssignedPatientReadPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class JpaAssignedPatientReadAdapter implements AssignedPatientReadPort {
    // Shared exact predicate for count, page, detail, and private subject resolution.
    static final String SCOPE=" exists (select a.id from DoctorPatientAssignmentEntity a where a.clinicalPatientId=p.id and a.doctorSubject=:doctor and a.status='ACTIVE') ";
    private final EntityManager em;
    public JpaAssignedPatientReadAdapter(EntityManager em) { this.em=Objects.requireNonNull(em); }
    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Page list(String doctor,int offset,int size) {
        var total=em.createQuery("select count(p.id) from ClinicalPatientEntity p where"+SCOPE,Long.class).setParameter("doctor",doctor).getSingleResult();
        var rows=em.createQuery("select p.id,p.status,p.createdAt,p.updatedAt from ClinicalPatientEntity p where"+SCOPE+"order by p.createdAt desc,p.id asc",Object[].class)
                .setParameter("doctor",doctor).setFirstResult(offset).setMaxResults(size).getResultList();
        return new Page(rows.stream().map(r->new Patient((UUID)r[0],(String)r[1],(Instant)r[2],(Instant)r[3],null,List.of())).toList(),total);
    }
    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<Patient> detail(String doctor,UUID id) {
        var rows=em.createQuery("select p.id,p.status,p.createdAt,p.updatedAt,p.strokeDiagnosis,p.sequelae from ClinicalPatientEntity p where p.id=:id and"+SCOPE,Object[].class)
                .setParameter("id",id).setParameter("doctor",doctor).setMaxResults(1).getResultList();
        return rows.stream().findFirst().map(r->new Patient((UUID)r[0],(String)r[1],(Instant)r[2],(Instant)r[3],(String)r[4],Arrays.stream((String[])r[5]).sorted().toList()));
    }
    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Map<UUID,String> resolve(String doctor,List<UUID> ids) {
        var rows=em.createQuery("select p.id,p.patientSubject from ClinicalPatientEntity p where p.id in :ids and"+SCOPE,Object[].class)
                .setParameter("ids",ids).setParameter("doctor",doctor).setMaxResults(100).getResultList();
        var result=new LinkedHashMap<UUID,String>();
        for(var row:rows) if(result.putIfAbsent((UUID)row[0],(String)row[1])!=null) throw new IllegalStateException("Ambiguous patient binding");
        return result;
    }
}
