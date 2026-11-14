package sn.recozai.audit.api;

import java.util.UUID;

public interface Audit {
    void record(UUID actorId, UUID clinicId, String action, UUID subjectId);
}
