package sn.recozai.audit.adapter;

import org.springframework.stereotype.Repository;
import sn.recozai.audit.api.Audit;
import sn.recozai.audit.application.AuditQuery;
import sn.recozai.shared.Page;
import sn.recozai.platform.Database;
import java.util.UUID;

@Repository
public class JpaAudit implements Audit, AuditQuery.Store {
    private final Database db;
    public JpaAudit(Database db) { this.db = db; }
    @Override public void record(UUID actor, UUID clinic, String action, UUID subject) {
        db.execute("INSERT INTO audit_event(id,actor_id,clinic_id,scope,action,subject_id) VALUES(:id,:actor,:clinic,:scope,:action,:subject)",
            "id", UUID.randomUUID(), "actor", actor, "clinic", clinic, "scope", clinic == null ? "IDENTITY" : "CLINIC", "action", action, "subject", subject);
    }
    @Override public Page<AuditQuery.Event> events(UUID clinic,int page) {
        var rows=db.rows("SELECT * FROM audit_event WHERE clinic_id=:clinic AND scope='CLINIC' ORDER BY occurred_at DESC,id LIMIT 25 OFFSET :offset",
            "clinic",clinic,"offset",page*25).stream().map(r->new AuditQuery.Event(Database.uuid(r,"id"),Database.text(r,"action"),
                Database.instant(r,"occurred_at"),Database.uuid(r,"actor_id"),Database.uuid(r,"subject_id"))).toList();
        long count=Database.number(db.rows("SELECT count(*) AS total FROM audit_event WHERE clinic_id=:clinic AND scope='CLINIC'","clinic",clinic).getFirst(),"total");
        return new Page<>(rows,page,25,count);
    }
}
