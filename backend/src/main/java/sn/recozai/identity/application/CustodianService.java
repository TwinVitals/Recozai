package sn.recozai.identity.application;

import sn.recozai.access.Role;
import sn.recozai.audit.api.Audit;
import sn.recozai.identity.domain.Identity.*;
import sn.recozai.shared.*;
import java.time.*;
import java.util.*;

/**
 * Identity-custodian use case: BOOTSTRAP, ENROLL and RECOVER from an independently verified, signed grant.
 * Never reachable through clinic RBAC. Signature verification and envelope parsing are inbound-adapter
 * concerns ({@code config.IdentityOperator}); this class receives an already authenticated {@link Grant}
 * and enforces the business rules.
 */
public final class CustodianService {
    public record Grant(UUID nonce, String action, String login, String clinicName,
                        String custodian, String verificationReference, Instant expiresAt, String securityEpoch) {}

    private static final Set<String> ACTIONS = Set.of("BOOTSTRAP", "ENROLL", "RECOVER");
    private final IdentityStore identities;
    private final ClinicProvisioning clinics;
    private final Credentials crypto;
    private final Transactions tx;
    private final Audit audit;
    private final Clock clock;
    private final String epoch;

    public CustodianService(IdentityStore identities, ClinicProvisioning clinics, Credentials crypto,
                            Transactions tx, Audit audit, Clock clock, String epoch) {
        this.identities = identities; this.clinics = clinics; this.crypto = crypto;
        this.tx = tx; this.audit = audit; this.clock = clock; this.epoch = epoch;
    }

    /** Returns the one-use challenge token for out-of-band delivery to the verified owner. */
    public String issue(Grant grant) {
        validate(grant);
        return tx.execute(() -> {
            identities.consumeOperatorGrant(grant.nonce(), grant.custodian(), grant.verificationReference(), grant.action(), epoch);
            String login = IdentityService.normalizeLogin(grant.login());
            UUID userId;
            boolean enrollment = Set.of("BOOTSTRAP", "ENROLL").contains(grant.action());
            if (enrollment) {
                if ("BOOTSTRAP".equals(grant.action()) && identities.identityExists())
                    throw new Failure(409, "BOOTSTRAP_ALREADY_COMPLETED");
                userId = UUID.randomUUID(); identities.createUser(userId, login);
            } else {
                User user = identities.user(login).orElseThrow(() -> new Failure(404, "NOT_FOUND"));
                if (!user.enabled()) throw new Failure(409, "IDENTITY_DISABLED");
                userId = user.id();
            }
            if ("BOOTSTRAP".equals(grant.action())) {
                UUID clinic = clinics.provision(grant.clinicName());
                identities.addMember(clinic, userId, Set.of(Role.CLINIC_ADMIN));
                audit.record(null, clinic, "CLINIC_BOOTSTRAPPED", grant.nonce());
            }
            String token = crypto.token();
            identities.addChallenge(new Challenge(UUID.randomUUID(), userId, enrollment ? "ENROLL" : "RECOVER",
                identities.user(userId).credentialVersion(), epoch, clock.instant().plus(Duration.ofMinutes(15)), false), crypto.hash(token));
            audit.record(null, null, "CUSTODIAN_" + grant.action(), grant.nonce());
            return token;
        });
    }

    private void validate(Grant grant) {
        Instant now = clock.instant();
        if (grant == null || grant.nonce() == null || grant.login() == null || grant.expiresAt() == null
            || !now.isBefore(grant.expiresAt()) || grant.expiresAt().isAfter(now.plus(Duration.ofMinutes(15)))
            || !epoch.equals(grant.securityEpoch()) || blankOrLonger(grant.custodian(), 120)
            || blankOrLonger(grant.verificationReference(), 160) || grant.action() == null || !ACTIONS.contains(grant.action()))
            throw new Failure(403, "INVALID_OPERATOR_GRANT");
    }

    private static boolean blankOrLonger(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
}
