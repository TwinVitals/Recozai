package sn.recozai.access;

import sn.recozai.shared.Failure;
import java.util.*;

/** Authenticated principal and its selected clinic membership; never supplied by the client. */
public record Actor(UUID sessionId, UUID userId, UUID clinicId, UUID membershipId,
                    Set<Role> roles, String locale) {
    private static final Set<Role> PAYER_READ_ROLES = EnumSet.of(
        Role.CLINIC_ADMIN, Role.BILLING_MANAGER, Role.BILLING_STAFF, Role.CLAIMS_REVIEWER);
    private static final Set<Role> CLAIM_READ_ROLES = EnumSet.of(
        Role.BILLING_MANAGER, Role.BILLING_STAFF, Role.CLAIMS_REVIEWER);
    private static final Set<Role> CLAIM_WRITE_ROLES = EnumSet.of(
        Role.BILLING_MANAGER, Role.BILLING_STAFF);
    public Actor { roles = Set.copyOf(roles); }
    public void requireAdmin() {
        if (!roles.contains(Role.CLINIC_ADMIN)) throw new Failure(403, "FORBIDDEN");
    }
    public void requirePayerRead() {
        if (clinicId == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
        if (Collections.disjoint(roles, PAYER_READ_ROLES)) throw new Failure(403, "FORBIDDEN");
    }
    public void requirePayerManage() {
        if (clinicId == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
        if (!roles.contains(Role.BILLING_MANAGER)) throw new Failure(403, "FORBIDDEN");
    }
    public void requireClaimRead() {
        if (clinicId == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
        if (Collections.disjoint(roles, CLAIM_READ_ROLES)) throw new Failure(403, "FORBIDDEN");
    }
    public void requireClaimWrite() {
        if (clinicId == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
        if (Collections.disjoint(roles, CLAIM_WRITE_ROLES)) throw new Failure(403, "FORBIDDEN");
    }
    public void requireClaimManage() {
        if (clinicId == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
        if (!roles.contains(Role.BILLING_MANAGER)) throw new Failure(403, "FORBIDDEN");
    }
    public void requireLedgerRead() {
        requireClaimRead();
    }
    public void requireLedgerWrite() {
        requireClaimWrite();
    }
    public void requireWriteOffApproval() {
        requireClaimManage();
    }
    public void requireDenialRead() {
        requireClaimRead();
    }
    public void requireDenialWrite() {
        requireClaimRead();
    }
    public void requireAppealApproval() {
        requireClaimManage();
    }
}
