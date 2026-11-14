package sn.recozai.identity.domain;

import sn.recozai.access.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class Identity {
    private Identity() {}

    public record User(UUID id, String login, String passwordHash, String locale,
                       boolean enabled, long credentialVersion) {}

    public record Membership(UUID id, UUID clinicId, UUID userId, String login,
                             boolean active, long version, Set<Role> roles) {
        public Membership { roles = Set.copyOf(roles); }
    }

    public record Session(UUID id, UUID userId, UUID clinicId, UUID membershipId,
                          long credentialVersion, long membershipVersion, String epoch,
                          Instant createdAt, Instant lastSeen, boolean revoked) {}

    public record Challenge(UUID id, UUID userId, String purpose, long credentialVersion,
                            String epoch, Instant expiresAt, boolean consumed) {}

    public record Invitation(UUID id, UUID clinicId, String login, Set<Role> roles,
                             String epoch, Instant expiresAt, boolean consumed, UUID issuerMembershipId,
                             Long issuerMembershipVersion, Long issuerCredentialVersion) {}
}
