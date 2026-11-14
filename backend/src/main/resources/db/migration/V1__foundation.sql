CREATE TABLE clinic (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    locale varchar(5) NOT NULL CHECK (locale IN ('fr-SN','en')),
    timezone varchar(80) NOT NULL,
    currency char(3) NOT NULL,
    fraction_digits integer NOT NULL CHECK (fraction_digits BETWEEN 0 AND 6),
    version bigint NOT NULL DEFAULT 0
);
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    normalized_login varchar(120) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL DEFAULT '',
    locale varchar(5) NOT NULL DEFAULT 'fr-SN' CHECK (locale IN ('fr-SN','en')),
    enabled boolean NOT NULL DEFAULT false,
    credential_version bigint NOT NULL DEFAULT 0
);
CREATE TABLE clinic_membership (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    user_id uuid NOT NULL REFERENCES app_user(id),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (clinic_id,user_id),
    UNIQUE (clinic_id,id)
);
CREATE TABLE membership_role (
    clinic_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    role_code varchar(30) NOT NULL CHECK (role_code IN
        ('CLINIC_ADMIN','BILLING_MANAGER','BILLING_STAFF','CLAIMS_REVIEWER','MANAGEMENT_VIEWER')),
    PRIMARY KEY (clinic_id,membership_id,role_code),
    FOREIGN KEY (clinic_id,membership_id) REFERENCES clinic_membership(clinic_id,id)
);
CREATE TABLE auth_session (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES app_user(id),
    clinic_id uuid,
    membership_id uuid,
    credential_version bigint NOT NULL,
    membership_version bigint NOT NULL,
    security_epoch varchar(120) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    FOREIGN KEY (clinic_id,membership_id) REFERENCES clinic_membership(clinic_id,id),
    CHECK ((clinic_id IS NULL) = (membership_id IS NULL))
);
CREATE INDEX auth_session_user ON auth_session(user_id);
CREATE INDEX auth_session_member ON auth_session(clinic_id,membership_id);
CREATE TABLE auth_challenge (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    token_hash char(64) NOT NULL UNIQUE,
    purpose varchar(10) NOT NULL CHECK (purpose IN ('ENROLL','RECOVER')),
    credential_version bigint NOT NULL,
    security_epoch varchar(120) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX auth_challenge_user ON auth_challenge(user_id);
CREATE TABLE auth_factor (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    kind varchar(30) NOT NULL,
    encrypted_secret bytea NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX auth_factor_user ON auth_factor(user_id);
CREATE TABLE membership_invitation (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    intended_login varchar(120) NOT NULL,
    roles varchar(200) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    security_epoch varchar(120) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX membership_invitation_clinic ON membership_invitation(clinic_id);
CREATE TABLE operator_authorization (
    nonce uuid PRIMARY KEY,
    consumed_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_id uuid,
    clinic_id uuid REFERENCES clinic(id),
    scope varchar(10) NOT NULL CHECK (scope IN ('CLINIC','IDENTITY')),
    action varchar(80) NOT NULL,
    subject_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp,
    CHECK ((scope='CLINIC') = (clinic_id IS NOT NULL))
);
CREATE INDEX audit_event_scope ON audit_event(clinic_id,occurred_at,id);
CREATE FUNCTION prevent_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AUDIT_IMMUTABLE';
END;
$$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
CREATE TABLE auth_rate_limit (
    key_hash char(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL
);
