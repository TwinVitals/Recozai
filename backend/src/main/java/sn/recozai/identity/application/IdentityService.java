package sn.recozai.identity.application;

import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.identity.domain.Identity.*;
import sn.recozai.shared.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class IdentityService implements Access {
    private final IdentityStore store;
    private final Credentials crypto;
    private final Transactions transactions;
    private final Audit audit;
    private final Clock clock;
    private final String epoch;
    private final String dummyHash;

    public IdentityService(IdentityStore store, Credentials crypto, Transactions transactions,
                           Audit audit, Clock clock, String epoch) {
        this.store = store; this.crypto = crypto; this.transactions = transactions;
        this.audit = audit; this.clock = clock; this.epoch = epoch;
        this.dummyHash = crypto.encode(crypto.token());
    }

    public record LoginResult(String token, Actor actor) {}
    public record InviteResult(UUID id, String token) {}
    public record Profile(UUID id, String login, String locale, UUID clinicId,
                          UUID membershipId, Set<Role> roles, List<Membership> memberships) {}

    public LoginResult login(String login, String password, UUID membershipId) {
        return transactions.execute(() -> {
            User user = store.user(normalizeLogin(login)).orElse(null);
            boolean matches = crypto.matches(password, user == null ? dummyHash : user.passwordHash());
            if (user == null || !matches || !user.enabled()) throw new Failure(401, "INVALID_CREDENTIALS");
            List<Membership> memberships = store.memberships(user.id()).stream().filter(Membership::active).toList();
            Membership selected = membershipId == null
                ? (memberships.size() == 1 ? memberships.getFirst() : null)
                : memberships.stream().filter(m -> m.id().equals(membershipId)).findFirst()
                    .orElseThrow(() -> new Failure(401, "INVALID_CREDENTIALS"));
            Instant now = clock.instant();
            String token = crypto.token();
            Session session = new Session(UUID.randomUUID(), user.id(),
                selected == null ? null : selected.clinicId(), selected == null ? null : selected.id(),
                user.credentialVersion(), selected == null ? 0 : selected.version(), epoch, now, now, false);
            store.addSession(session, crypto.hash(token));
            audit.record(user.id(), session.clinicId(), "SESSION_CREATED", user.id());
            return new LoginResult(token, resolve(session));
        });
    }

    public Optional<Actor> authenticate(String token) {
        return transactions.execute(() -> store.session(crypto.hash(token)).map(session -> {
            Actor actor = resolve(session);
            store.touchSession(session.id(), clock.instant());
            return actor;
        }));
    }

    public LoginResult selectMembership(Actor actor, UUID membershipId) {
        return transactions.execute(() -> {
            Actor current=current(actor);
            Membership selected=store.memberships(current.userId()).stream()
                .filter(m -> m.id().equals(membershipId) && m.active()).findFirst()
                .orElseThrow(()->new Failure(404,"NOT_FOUND"));
            String token=crypto.token();
            Instant now=clock.instant();
            Session session=new Session(UUID.randomUUID(),current.userId(),selected.clinicId(),selected.id(),
                store.user(current.userId()).credentialVersion(),selected.version(),epoch,now,now,false);
            store.revokeSession(current.sessionId());
            store.addSession(session,crypto.hash(token));
            audit.record(current.userId(),selected.clinicId(),"SESSION_CREATED",current.userId());
            return new LoginResult(token,resolve(session));
        });
    }

    @Override public Actor current(Actor supplied) {
        if (supplied == null) throw new Failure(401, "AUTHENTICATION_REQUIRED");
        return resolve(store.session(supplied.sessionId())
            .orElseThrow(() -> new Failure(401, "SESSION_EXPIRED")));
    }

    private Actor resolve(Session session) {
        Instant now = clock.instant();
        if (session.revoked() || !epoch.equals(session.epoch())
            || !now.isBefore(session.createdAt().plus(Duration.ofHours(8)))
            || !now.isBefore(session.lastSeen().plus(Duration.ofMinutes(30))))
            throw new Failure(401, "SESSION_EXPIRED");
        User user = store.user(session.userId());
        if (!user.enabled() || user.credentialVersion() != session.credentialVersion())
            throw new Failure(401, "SESSION_EXPIRED");
        Membership member = session.membershipId() == null ? null : store.member(session.clinicId(), session.membershipId());
        if (member != null && (!member.active() || !member.userId().equals(user.id())
            || member.version() != session.membershipVersion()))
            throw new Failure(401, "SESSION_EXPIRED");
        return new Actor(session.id(), user.id(), session.clinicId(), session.membershipId(),
            member == null ? Set.of() : member.roles(), user.locale());
    }

    public Profile profile(Actor actor) {
        return transactions.execute(() -> {
            Actor active = current(actor);
            User user = store.user(active.userId());
            return new Profile(user.id(), user.login(), user.locale(), active.clinicId(),
                active.membershipId(), active.roles(), store.memberships(user.id()));
        });
    }

    public void logout(Actor actor) {
        transactions.execute(() -> {
            Actor active = current(actor); store.revokeSession(active.sessionId());
            audit.record(active.userId(), active.clinicId(), "SESSION_REVOKED", active.userId()); return null;
        });
    }

    public void locale(Actor actor, String locale) {
        if (!Set.of("fr-SN", "en").contains(locale)) throw new Failure(422, "INVALID_LOCALE");
        transactions.execute(() -> {
            Actor active = current(actor); store.locale(active.userId(), locale);
            audit.record(active.userId(), null, "LOCALE_CHANGED", active.userId()); return null;
        });
    }

    public Page<Membership> members(Actor actor, int page) {
        return transactions.execute(() -> {
            Actor active = current(actor); active.requireAdmin();
            if (page < 0 || page > 10000) throw new Failure(400, "INVALID_PAGE");
            return new Page<>(store.members(active.clinicId(),page),page,25,store.memberCount(active.clinicId()));
        });
    }

    public void updateMember(Actor actor, UUID memberId, boolean enabled, Set<Role> roles, long version) {
        validateRoles(roles);
        transactions.execute(() -> {
            Actor active = current(actor); active.requireAdmin();
            if (active.membershipId().equals(memberId)) throw new Failure(409, "SELF_MEMBERSHIP_CHANGE_DENIED");
            store.updateMember(active.clinicId(), memberId, enabled, roles, version);
            audit.record(active.userId(), active.clinicId(), "MEMBERSHIP_CHANGED", memberId); return null;
        });
    }

    public void updateRoles(Actor actor, UUID memberId, Set<Role> roles, long version) {
        transactions.execute(() -> {
            Actor active=current(actor); active.requireAdmin();
            updateMember(active,memberId,store.member(active.clinicId(),memberId).active(),roles,version);
            return null;
        });
    }

    public void revokeMember(Actor actor, UUID memberId) {
        transactions.execute(() -> {
            Actor active = current(actor); active.requireAdmin();
            store.member(active.clinicId(), memberId);
            store.revokeMember(active.clinicId(), memberId);
            audit.record(active.userId(), active.clinicId(), "MEMBERSHIP_SESSIONS_REVOKED", memberId); return null;
        });
    }

    public InviteResult invite(Actor actor, String login, Set<Role> roles) {
        validateRoles(roles);
        return transactions.execute(() -> {
            Actor active = current(actor); active.requireAdmin();
            UUID id = UUID.randomUUID(); String token = crypto.token();
            store.addInvitation(new Invitation(id, active.clinicId(), normalizeLogin(login), roles,
                epoch, clock.instant().plus(Duration.ofDays(1)), false, active.membershipId(),
                store.member(active.clinicId(),active.membershipId()).version(),store.user(active.userId()).credentialVersion()), crypto.hash(token));
            audit.record(active.userId(), active.clinicId(), "MEMBERSHIP_INVITED", id);
            return new InviteResult(id, token);
        });
    }

    public void accept(Actor actor, UUID id, String token) {
        transactions.execute(() -> {
            Actor active = current(actor);
            Invitation invitation = store.invitation(id, crypto.hash(token))
                .orElseThrow(() -> new Failure(422, "INVALID_INVITATION"));
            if (invitation.consumed() || !epoch.equals(invitation.epoch())
                || !clock.instant().isBefore(invitation.expiresAt())
                || !store.user(active.userId()).login().equals(invitation.login())
                || invitation.issuerMembershipId()==null || invitation.issuerMembershipVersion()==null || invitation.issuerCredentialVersion()==null)
                throw new Failure(422, "INVALID_INVITATION");
            Membership issuer=store.member(invitation.clinicId(),invitation.issuerMembershipId());
            User issuerUser=store.user(issuer.userId());
            if(!issuer.active() || !issuer.roles().contains(Role.CLINIC_ADMIN) || !issuerUser.enabled()
                || issuer.version()!=invitation.issuerMembershipVersion() || issuerUser.credentialVersion()!=invitation.issuerCredentialVersion())
                throw new Failure(422,"INVALID_INVITATION");
            store.addMember(invitation.clinicId(), active.userId(), invitation.roles());
            store.consumeInvitation(id);
            audit.record(active.userId(), invitation.clinicId(), "MEMBERSHIP_ACCEPTED", id); return null;
        });
    }

    public void changePassword(Actor actor, String oldPassword, String newPassword) {
        validatePassword(newPassword);
        transactions.execute(() -> {
            Actor active = current(actor);
            if (!crypto.matches(oldPassword, store.user(active.userId()).passwordHash()))
                throw new Failure(401, "INVALID_CREDENTIALS");
            store.credentials(active.userId(), crypto.encode(newPassword), false);
            audit.record(active.userId(), null, "OWN_PASSWORD_CHANGED", active.userId()); return null;
        });
    }

    public void revokeOwnSessions(Actor actor, String password) {
        transactions.execute(() -> {
            Actor active = current(actor);
            if (!crypto.matches(password, store.user(active.userId()).passwordHash()))
                throw new Failure(401, "INVALID_CREDENTIALS");
            store.revokeUser(active.userId());
            audit.record(active.userId(), null, "OWN_SESSIONS_REVOKED", active.userId()); return null;
        });
    }

    public void completeChallenge(String token, String password, String purpose) {
        validatePassword(password);
        transactions.execute(() -> {
            Challenge challenge = store.challenge(crypto.hash(token))
                .orElseThrow(() -> new Failure(422, "INVALID_CHALLENGE"));
            User user = store.user(challenge.userId());
            if (challenge.consumed() || !purpose.equals(challenge.purpose()) || !epoch.equals(challenge.epoch())
                || !clock.instant().isBefore(challenge.expiresAt())
                || challenge.credentialVersion() != user.credentialVersion()
                || ("RECOVER".equals(purpose) && !user.enabled()))
                throw new Failure(422, "INVALID_CHALLENGE");
            store.consumeChallenge(challenge.id());
            store.credentials(user.id(), crypto.encode(password), "ENROLL".equals(purpose));
            audit.record(user.id(), null, "IDENTITY_" + purpose + "_COMPLETED", user.id()); return null;
        });
    }

    public static String normalizeLogin(String login) {
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9@._+-]{2,119}")) throw new Failure(422, "INVALID_LOGIN");
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new Failure(422, "INVALID_PASSWORD");
    }

    private static void validateRoles(Set<Role> roles) {
        if (roles==null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull))
            throw new Failure(422,"ROLES_REQUIRED");
    }
}
