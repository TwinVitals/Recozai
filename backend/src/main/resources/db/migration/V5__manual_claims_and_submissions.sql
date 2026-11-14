CREATE TABLE claim (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    payer_id uuid NOT NULL,
    external_reference varchar(120) NOT NULL,
    pseudonymous_patient_ref varchar(120) NOT NULL,
    currency char(3) NOT NULL,
    currency_scale integer NOT NULL CHECK (currency_scale BETWEEN 0 AND 6),
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT','REGISTERED','SUBMITTED','VOIDED','ARCHIVED')),
    draft_source_date date,
    active_revision_id uuid,
    state_reason varchar(240),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, payer_id, external_reference),
    FOREIGN KEY (clinic_id, payer_id) REFERENCES payer(clinic_id, id),
    CHECK ((state = 'DRAFT') = (active_revision_id IS NULL))
);
CREATE INDEX claim_clinic_state ON claim(clinic_id, state, updated_at DESC, id);

CREATE TABLE claim_draft_line (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    line_number integer NOT NULL CHECK (line_number >= 1),
    service_code varchar(80) NOT NULL,
    description varchar(240) NOT NULL,
    submitted_amount numeric(20,6) NOT NULL CHECK (submitted_amount > 0),
    UNIQUE (clinic_id, claim_id, line_number),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id) ON DELETE CASCADE
);
CREATE INDEX claim_draft_line_claim ON claim_draft_line(clinic_id, claim_id, line_number);

CREATE TABLE claim_revision (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number >= 1),
    submitted_amount numeric(20,6) NOT NULL CHECK (submitted_amount > 0),
    source_date date NOT NULL,
    content_hash char(64) NOT NULL,
    change_reason varchar(240),
    evidence_document_id uuid,
    registered_by uuid NOT NULL REFERENCES app_user(id),
    registered_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, claim_id, id),
    UNIQUE (clinic_id, claim_id, revision_number),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id),
    CHECK (revision_number = 1 OR (change_reason IS NOT NULL AND btrim(change_reason) <> ''))
);
CREATE INDEX claim_revision_claim ON claim_revision(clinic_id, claim_id, revision_number DESC, id);

ALTER TABLE claim
    ADD CONSTRAINT claim_active_revision_fk
    FOREIGN KEY (clinic_id, id, active_revision_id)
    REFERENCES claim_revision(clinic_id, claim_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE claim_line (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    line_number integer NOT NULL CHECK (line_number >= 1),
    service_code varchar(80) NOT NULL,
    description varchar(240) NOT NULL,
    submitted_amount numeric(20,6) NOT NULL CHECK (submitted_amount > 0),
    UNIQUE (clinic_id, revision_id, line_number),
    FOREIGN KEY (clinic_id, claim_id, revision_id) REFERENCES claim_revision(clinic_id, claim_id, id)
);
CREATE INDEX claim_line_revision ON claim_line(clinic_id, revision_id, line_number);

CREATE TABLE claim_submission (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    event_kind varchar(30) NOT NULL CHECK (event_kind IN ('SUBMISSION','RESUBMISSION','METADATA_CORRECTION')),
    channel_code varchar(60) NOT NULL,
    external_submission_ref varchar(120) NOT NULL,
    occurred_on date NOT NULL,
    occurred_at timestamptz,
    occurrence_precision varchar(10) NOT NULL CHECK (occurrence_precision IN ('DATE','INSTANT')),
    evidence_document_id uuid,
    resubmission_of_id uuid,
    supersedes_id uuid UNIQUE,
    correction_reason varchar(240),
    idempotency_key varchar(120) NOT NULL,
    request_hash char(64) NOT NULL,
    recorded_by uuid NOT NULL REFERENCES app_user(id),
    recorded_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, claim_id, id),
    UNIQUE (clinic_id, claim_id, idempotency_key),
    FOREIGN KEY (clinic_id, claim_id, revision_id) REFERENCES claim_revision(clinic_id, claim_id, id),
    FOREIGN KEY (clinic_id, claim_id, resubmission_of_id) REFERENCES claim_submission(clinic_id, claim_id, id),
    FOREIGN KEY (clinic_id, claim_id, supersedes_id) REFERENCES claim_submission(clinic_id, claim_id, id),
    CHECK ((occurrence_precision = 'INSTANT') = (occurred_at IS NOT NULL)),
    CHECK (
        (event_kind = 'SUBMISSION' AND resubmission_of_id IS NULL AND supersedes_id IS NULL)
        OR (event_kind = 'RESUBMISSION' AND resubmission_of_id IS NOT NULL AND supersedes_id IS NULL AND correction_reason IS NOT NULL AND btrim(correction_reason) <> '')
        OR (event_kind = 'METADATA_CORRECTION' AND supersedes_id IS NOT NULL AND resubmission_of_id IS NULL AND correction_reason IS NOT NULL AND btrim(correction_reason) <> '')
    )
);
CREATE INDEX claim_submission_claim ON claim_submission(clinic_id, claim_id, recorded_at, id);

CREATE FUNCTION prevent_claim_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'CLAIM_HISTORY_IMMUTABLE';
END;
$$;
CREATE TRIGGER immutable_claim_revision BEFORE UPDATE OR DELETE ON claim_revision
    FOR EACH ROW EXECUTE FUNCTION prevent_claim_history_mutation();
CREATE TRIGGER immutable_claim_line BEFORE UPDATE OR DELETE ON claim_line
    FOR EACH ROW EXECUTE FUNCTION prevent_claim_history_mutation();
CREATE TRIGGER immutable_claim_submission BEFORE UPDATE OR DELETE ON claim_submission
    FOR EACH ROW EXECUTE FUNCTION prevent_claim_history_mutation();
