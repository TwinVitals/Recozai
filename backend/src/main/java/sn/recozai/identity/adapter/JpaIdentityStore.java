package sn.recozai.identity.adapter;

import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import sn.recozai.access.Role;
import sn.recozai.identity.application.IdentityStore;
import sn.recozai.identity.domain.Identity.*;
import sn.recozai.platform.Database;
import sn.recozai.shared.Failure;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaIdentityStore implements IdentityStore {
    private final Database db;
    private final UserRepository users;
    public JpaIdentityStore(Database db, UserRepository users) { this.db = db; this.users=users; }
    @Override public Optional<User> user(String login) {
        return users.findByNormalizedLogin(login).map(this::user);
    }
    @Override public User user(UUID id) {
        return users.findById(id).map(this::user)
            .orElseThrow(() -> new Failure(401, "SESSION_EXPIRED"));
    }
    private User user(UserEntity r) {
        return new User(r.id,r.normalizedLogin,r.passwordHash,r.locale,r.enabled,r.credentialVersion);
    }
    @Override public void createUser(UUID id, String login) {
        users.saveAndFlush(new UserEntity(id,login));
    }
    @Override public void credentials(UUID id, String password, boolean activate) {
        UserEntity user=users.findById(id).orElseThrow(()->new Failure(404,"NOT_FOUND"));
        user.passwordHash=password; user.enabled=user.enabled || activate; user.credentialVersion++;
        users.saveAndFlush(user);
        db.execute("UPDATE auth_challenge SET consumed=true WHERE user_id=:id", "id", id);
        if (!activate) db.execute("UPDATE membership_invitation SET consumed=true WHERE intended_login=(SELECT normalized_login FROM app_user WHERE id=:id)", "id", id);
    }
    @Override public void locale(UUID userId, String locale) {
        UserEntity user=users.findById(userId).orElseThrow(()->new Failure(404,"NOT_FOUND"));
        user.locale=locale; users.saveAndFlush(user);
    }
    @Override public List<Membership> memberships(UUID userId) {
        return db.rows("SELECT m.*,u.normalized_login FROM clinic_membership m JOIN app_user u ON u.id=m.user_id WHERE m.user_id=:user ORDER BY m.id", "user", userId)
            .stream().map(this::membership).toList();
    }
    @Override public List<Membership> members(UUID clinicId, int page) {
        return db.rows("SELECT m.*,u.normalized_login FROM clinic_membership m JOIN app_user u ON u.id=m.user_id WHERE m.clinic_id=:clinic ORDER BY m.id LIMIT 25 OFFSET :offset",
            "clinic", clinicId, "offset", page * 25).stream().map(this::membership).toList();
    }
    @Override public Membership member(UUID clinicId, UUID id) {
        return db.rows("SELECT m.*,u.normalized_login FROM clinic_membership m JOIN app_user u ON u.id=m.user_id WHERE m.clinic_id=:clinic AND m.id=:id FOR UPDATE OF m",
            "clinic", clinicId, "id", id).stream().findFirst().map(this::membership)
            .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
    }
    @Override public long memberCount(UUID clinicId) {
        return number(db.rows("SELECT count(*) AS total FROM clinic_membership WHERE clinic_id=:clinic","clinic",clinicId).getFirst(),"total");
    }
    private Membership membership(Tuple r) {
        UUID clinic = uuid(r,"clinic_id"), id = uuid(r,"id");
        Set<Role> roles = db.rows("SELECT role_code FROM membership_role WHERE clinic_id=:clinic AND membership_id=:id",
            "clinic", clinic, "id", id).stream().map(row -> Role.valueOf(text(row,"role_code"))).collect(Collectors.toSet());
        return new Membership(id, clinic, uuid(r,"user_id"), text(r,"normalized_login"), flag(r,"active"), number(r,"version"), roles);
    }
    @Override public UUID addMember(UUID clinicId, UUID userId, Set<Role> roles) {
        UUID id = UUID.randomUUID();
        db.execute("INSERT INTO clinic_membership(id,clinic_id,user_id) VALUES(:id,:clinic,:user)", "id", id, "clinic", clinicId, "user", userId);
        addRoles(clinicId, id, roles);
        return id;
    }
    private void addRoles(UUID clinic, UUID id, Set<Role> roles) {
        for (Role role : roles) db.execute("INSERT INTO membership_role(clinic_id,membership_id,role_code) VALUES(:clinic,:id,:role)",
            "clinic", clinic, "id", id, "role", role.name());
    }
    @Override public void updateMember(UUID clinic, UUID id, boolean active, Set<Role> roles, long expectedVersion) {
        member(clinic, id);
        if (db.execute("UPDATE clinic_membership SET active=:active,version=version+1 WHERE clinic_id=:clinic AND id=:id AND version=:version",
            "clinic", clinic, "id", id, "active", active, "version", expectedVersion) != 1)
            throw new Failure(412, "STALE_VERSION");
        db.execute("DELETE FROM membership_role WHERE clinic_id=:clinic AND membership_id=:id", "clinic", clinic, "id", id);
        addRoles(clinic, id, roles);
    }
    @Override public void addSession(Session s, String hash) {
        db.execute("""
            INSERT INTO auth_session(id,token_hash,user_id,clinic_id,membership_id,credential_version,membership_version,security_epoch,created_at,last_seen)
            VALUES(:id,:hash,:user,:clinic,:member,:cv,:mv,:epoch,:created,:seen)
            """, "id", s.id(), "hash", hash, "user", s.userId(), "clinic", s.clinicId(), "member", s.membershipId(),
            "cv", s.credentialVersion(), "mv", s.membershipVersion(), "epoch", s.epoch(), "created", s.createdAt(), "seen", s.lastSeen());
    }
    @Override public Optional<Session> session(String hash) {
        return db.rows("SELECT * FROM auth_session WHERE token_hash=:hash", "hash", hash).stream().findFirst().map(this::session);
    }
    @Override public Optional<Session> session(UUID id) {
        return db.rows("SELECT * FROM auth_session WHERE id=:id", "id", id).stream().findFirst().map(this::session);
    }
    private Session session(Tuple r) {
        return new Session(uuid(r,"id"), uuid(r,"user_id"), uuid(r,"clinic_id"), uuid(r,"membership_id"),
            number(r,"credential_version"), number(r,"membership_version"), text(r,"security_epoch"),
            instant(r,"created_at"), instant(r,"last_seen"), flag(r,"revoked"));
    }
    @Override public void touchSession(UUID id, Instant now) {
        db.execute("UPDATE auth_session SET last_seen=:now WHERE id=:id", "id", id, "now", now);
    }
    @Override public void revokeSession(UUID id) {
        db.execute("UPDATE auth_session SET revoked=true WHERE id=:id", "id", id);
    }
    @Override public void revokeMember(UUID clinicId, UUID memberId) {
        db.execute("UPDATE clinic_membership SET version=version+1 WHERE clinic_id=:clinic AND id=:id", "clinic", clinicId, "id", memberId);
    }
    @Override public void revokeUser(UUID userId) {
        db.execute("UPDATE auth_session SET revoked=true WHERE user_id=:id", "id", userId);
    }
    @Override public void addChallenge(Challenge c, String hash) {
        db.execute("INSERT INTO auth_challenge(id,user_id,token_hash,purpose,credential_version,security_epoch,expires_at) VALUES(:id,:user,:hash,:purpose,:cv,:epoch,:expires)",
            "id", c.id(), "user", c.userId(), "hash", hash, "purpose", c.purpose(), "cv", c.credentialVersion(), "epoch", c.epoch(), "expires", c.expiresAt());
    }
    @Override public Optional<Challenge> challenge(String hash) {
        var existing=db.rows("SELECT user_id FROM auth_challenge WHERE token_hash=:hash", "hash", hash).stream().findFirst();
        if(existing.isEmpty()) return Optional.empty();
        user(uuid(existing.orElseThrow(),"user_id"));
        return db.rows("SELECT * FROM auth_challenge WHERE token_hash=:hash FOR UPDATE", "hash", hash).stream().findFirst()
            .map(r -> new Challenge(uuid(r,"id"), uuid(r,"user_id"), text(r,"purpose"), number(r,"credential_version"),
                text(r,"security_epoch"), instant(r,"expires_at"), flag(r,"consumed")));
    }
    @Override public void consumeChallenge(UUID id) { db.execute("UPDATE auth_challenge SET consumed=true WHERE id=:id", "id", id); }
    @Override public void addInvitation(Invitation i, String hash) {
        db.execute("INSERT INTO membership_invitation(id,clinic_id,intended_login,roles,token_hash,security_epoch,expires_at,issuer_membership_id,issuer_membership_version,issuer_credential_version) VALUES(:id,:clinic,:login,:roles,:hash,:epoch,:expires,:issuer,:mv,:cv)",
            "id", i.id(), "clinic", i.clinicId(), "login", i.login(), "roles", roleString(i.roles()), "hash", hash, "epoch", i.epoch(), "expires", i.expiresAt(),
            "issuer",i.issuerMembershipId(),"mv",i.issuerMembershipVersion(),"cv",i.issuerCredentialVersion());
    }
    @Override public Optional<Invitation> invitation(UUID id, String hash) {
        return db.rows("SELECT * FROM membership_invitation WHERE id=:id AND token_hash=:hash FOR UPDATE", "id", id, "hash", hash).stream().findFirst()
            .map(r -> new Invitation(uuid(r,"id"), uuid(r,"clinic_id"), text(r,"intended_login"),
                Arrays.stream(text(r,"roles").split(",")).map(Role::valueOf).collect(Collectors.toSet()),
                text(r,"security_epoch"), instant(r,"expires_at"), flag(r,"consumed"),uuid(r,"issuer_membership_id"),
                nullableLong(r,"issuer_membership_version"),nullableLong(r,"issuer_credential_version")));
    }
    private Long nullableLong(Tuple row,String name) {
        Number value=row.get(name,Number.class); return value==null?null:value.longValue();
    }
    private String roleString(Set<Role> roles) { return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(",")); }
    @Override public void consumeInvitation(UUID id) { db.execute("UPDATE membership_invitation SET consumed=true WHERE id=:id", "id", id); }
    @Override public void consumeOperatorGrant(UUID nonce,String custodian,String verification,String action,String epoch) {
        if (db.execute("INSERT INTO operator_authorization(nonce,custodian,verification_reference,action,security_epoch,provenance_status) VALUES(:nonce,:custodian,:verification,:action,:epoch,'VERIFIED') ON CONFLICT DO NOTHING",
            "nonce",nonce,"custodian",custodian,"verification",verification,"action",action,"epoch",epoch) != 1)
            throw new Failure(409, "OPERATOR_GRANT_USED");
    }
    @Override public boolean identityExists() {
        db.rows("SELECT true AS locked FROM pg_advisory_xact_lock(72632601)");
        return !db.rows("SELECT id FROM app_user LIMIT 1").isEmpty();
    }
}
