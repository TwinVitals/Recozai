package sn.recozai.identity.application;

import sn.recozai.access.Role;
import sn.recozai.identity.domain.Identity.*;
import java.time.Instant;
import java.util.*;

public interface IdentityStore {
    Optional<User> user(String login);
    User user(UUID id);
    void createUser(UUID id, String login);
    void credentials(UUID id, String encodedPassword, boolean activate);
    void locale(UUID userId, String locale);
    List<Membership> memberships(UUID userId);
    List<Membership> members(UUID clinicId, int page);
    long memberCount(UUID clinicId);
    Membership member(UUID clinicId, UUID id);
    UUID addMember(UUID clinicId, UUID userId, Set<Role> roles);
    void updateMember(UUID clinicId, UUID id, boolean active, Set<Role> roles, long expectedVersion);
    void addSession(Session session, String tokenHash);
    Optional<Session> session(String tokenHash);
    Optional<Session> session(UUID id);
    void touchSession(UUID id, Instant now);
    void revokeSession(UUID id);
    void revokeMember(UUID clinicId, UUID memberId);
    void revokeUser(UUID userId);
    void addChallenge(Challenge challenge, String tokenHash);
    Optional<Challenge> challenge(String tokenHash);
    void consumeChallenge(UUID id);
    void addInvitation(Invitation invitation, String tokenHash);
    Optional<Invitation> invitation(UUID id, String tokenHash);
    void consumeInvitation(UUID id);
    void consumeOperatorGrant(UUID nonce, String custodian, String verification, String action, String epoch);
    boolean identityExists();
}
