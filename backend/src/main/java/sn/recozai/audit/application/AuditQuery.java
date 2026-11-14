package sn.recozai.audit.application;

import sn.recozai.access.*;
import sn.recozai.shared.*;
import java.time.Instant;
import java.util.UUID;

public final class AuditQuery {
    public record Event(UUID id,String action,Instant occurredAt,UUID actorId,UUID subjectId) {}
    public interface Store { Page<Event> events(UUID clinic,int page); }
    private final Store store;
    private final Access access;
    private final Transactions tx;
    public AuditQuery(Store store,Access access,Transactions tx) { this.store=store; this.access=access; this.tx=tx; }
    public Page<Event> events(Actor actor,int page) {
        if(page<0 || page>10000) throw new Failure(400,"INVALID_PAGE");
        return tx.execute(()->{
            Actor current=access.current(actor); current.requireAdmin();
            return store.events(current.clinicId(),page);
        });
    }
}
