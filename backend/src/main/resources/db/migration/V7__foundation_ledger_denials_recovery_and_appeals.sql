-- ============================================================================
-- V7: Financial Journal, Payer Rule Provenance, Denials, Recovery Cases,
--     Recovery Tasks, and Versioned Appeals Foundation
-- ============================================================================
-- Forward-safe additive migration.
-- Rollback / Recovery Strategy:
--   1. Destructive down-migrations are prohibited in production to preserve
--      financial, claim, and audit history.
--   2. If rollback is required before production traffic writes to V7 tables,
--      hold traffic with RESTORE_GATE=CLOSED, restore the pre-V7 verified
--      database snapshot, rotate SECURITY_EPOCH, and reopen RESTORE_GATE=OPEN.
-- ============================================================================

-- 1. Extend payer_rule_version with explicit source_locator and verification_status
ALTER TABLE payer_rule_version
    ADD COLUMN source_locator varchar(160),
    ADD COLUMN verification_status varchar(32) NOT NULL DEFAULT 'UNVERIFIED_DRAFT';

DROP TRIGGER IF EXISTS payer_rule_version_immutable ON payer_rule_version;

UPDATE payer_rule_version
SET verification_status = 'SOURCE_VERIFIED',
    source_locator = COALESCE(source_locator, 'DOCUMENT_ROOT')
WHERE state IN ('APPROVED', 'SUPERSEDED');

ALTER TABLE payer_rule_version
    ADD CONSTRAINT payer_rule_version_verification_check
    CHECK (
        (state = 'DRAFT' AND verification_status = 'UNVERIFIED_DRAFT')
        OR (
            state IN ('APPROVED', 'SUPERSEDED')
            AND verification_status = 'SOURCE_VERIFIED'
            AND source_locator IS NOT NULL
            AND btrim(source_locator) <> ''
        )
    );

CREATE OR REPLACE FUNCTION block_payer_rule_version_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PAYER_RULE_VERSION_IMMUTABLE';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.clinic_id <> OLD.clinic_id
        OR NEW.payer_id <> OLD.payer_id
        OR NEW.version_number <> OLD.version_number
        OR NEW.rule_kind <> OLD.rule_kind
        OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
        OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
        OR NEW.config_json <> OLD.config_json
        OR NEW.created_by <> OLD.created_by
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'PAYER_RULE_VERSION_IMMUTABLE';
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state = 'APPROVED' THEN
        IF NEW.source_document_id IS NULL
            OR NEW.source_locator IS NULL
            OR btrim(NEW.source_locator) = ''
            OR NEW.verification_status <> 'SOURCE_VERIFIED'
            OR NEW.approved_by IS NULL
            OR NEW.approved_at IS NULL THEN
            RAISE EXCEPTION 'PAYER_RULE_VERSION_IMMUTABLE';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'APPROVED' AND NEW.state = 'SUPERSEDED' THEN
        IF NEW.source_document_id IS DISTINCT FROM OLD.source_document_id
            OR NEW.source_locator IS DISTINCT FROM OLD.source_locator
            OR NEW.verification_status IS DISTINCT FROM OLD.verification_status
            OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.deadline_basis IS DISTINCT FROM OLD.deadline_basis THEN
            RAISE EXCEPTION 'PAYER_RULE_VERSION_IMMUTABLE';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'PAYER_RULE_VERSION_IMMUTABLE';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payer_rule_version_immutable
    BEFORE UPDATE OR DELETE ON payer_rule_version
    FOR EACH ROW EXECUTE FUNCTION block_payer_rule_version_mutation();

-- 2. Append-only Financial Journal (financial_journal_entry)
--    Retains who (recorded_by), when (occurred_on, recorded_at), why (entry_type, reason),
--    source (payer_reference, source_document_id), and previous/resulting balances.
CREATE TABLE financial_journal_entry (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    entry_type varchar(40) NOT NULL CHECK (entry_type IN (
        'INITIAL_PAYMENT',
        'CONTRACTUAL_ADJUSTMENT',
        'DENIAL_DISPUTED',
        'RECOVERY_PAYMENT',
        'WRITE_OFF',
        'REVERSAL'
    )),
    amount numeric(20,6) NOT NULL CHECK (amount > 0),
    previous_outstanding_amount numeric(20,6) NOT NULL CHECK (previous_outstanding_amount >= 0),
    resulting_outstanding_amount numeric(20,6) NOT NULL CHECK (resulting_outstanding_amount >= 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    currency_scale integer NOT NULL CHECK (currency_scale BETWEEN 0 AND 6),
    occurred_on date NOT NULL,
    payer_reference varchar(120),
    source_document_id uuid,
    reverses_entry_id uuid,
    reason varchar(240) NOT NULL CHECK (btrim(reason) <> ''),
    idempotency_key varchar(120) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash char(64) NOT NULL,
    recorded_by uuid NOT NULL REFERENCES app_user(id),
    recorded_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, claim_id, idempotency_key),
    UNIQUE (clinic_id, reverses_entry_id),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id),
    FOREIGN KEY (clinic_id, claim_id, revision_id) REFERENCES claim_revision(clinic_id, claim_id, id),
    FOREIGN KEY (clinic_id, source_document_id) REFERENCES document(clinic_id, id),
    FOREIGN KEY (clinic_id, reverses_entry_id) REFERENCES financial_journal_entry(clinic_id, id),
    CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> id)
);
CREATE INDEX financial_journal_claim_idx
    ON financial_journal_entry(clinic_id, claim_id, revision_id, recorded_at ASC, id ASC);

CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'APPEND_ONLY_RECORD_IMMUTABLE: %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER financial_journal_entry_immutable
    BEFORE UPDATE OR DELETE ON financial_journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- 3. Immutable Denial Events (denial_event)
--    Preserves verbatim original_reason_text and source_reason_code alongside normalized category.
CREATE TABLE denial_event (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    payer_id uuid NOT NULL,
    ledger_entry_id uuid NOT NULL,
    denial_kind varchar(24) NOT NULL CHECK (denial_kind IN ('FULL', 'PARTIAL')),
    source_reason_code varchar(80),
    original_reason_text varchar(2000) NOT NULL CHECK (btrim(original_reason_text) <> ''),
    normalized_category_key varchar(80) NOT NULL,
    category_mapping_source varchar(32) NOT NULL CHECK (category_mapping_source IN (
        'APPROVED_PAYER_RULE',
        'MANUAL_STAFF_SELECTION'
    )),
    mapping_rule_version_id uuid,
    denied_amount numeric(20,6) NOT NULL CHECK (denied_amount > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    currency_scale integer NOT NULL CHECK (currency_scale BETWEEN 0 AND 6),
    notice_date date NOT NULL,
    received_date date NOT NULL CHECK (received_date >= notice_date),
    payer_reference varchar(120),
    source_document_id uuid,
    idempotency_key varchar(120) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash char(64) NOT NULL,
    recorded_by uuid NOT NULL REFERENCES app_user(id),
    recorded_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, claim_id, idempotency_key),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id),
    FOREIGN KEY (clinic_id, claim_id, revision_id) REFERENCES claim_revision(clinic_id, claim_id, id),
    FOREIGN KEY (clinic_id, payer_id) REFERENCES payer(clinic_id, id),
    FOREIGN KEY (clinic_id, normalized_category_key) REFERENCES denial_category(clinic_id, category_key),
    FOREIGN KEY (clinic_id, mapping_rule_version_id) REFERENCES payer_rule_version(clinic_id, id),
    FOREIGN KEY (clinic_id, ledger_entry_id) REFERENCES financial_journal_entry(clinic_id, id),
    FOREIGN KEY (clinic_id, source_document_id) REFERENCES document(clinic_id, id),
    CHECK ((category_mapping_source = 'APPROVED_PAYER_RULE') = (mapping_rule_version_id IS NOT NULL))
);
CREATE INDEX denial_event_claim_idx
    ON denial_event(clinic_id, claim_id, recorded_at DESC, id DESC);
CREATE INDEX denial_event_payer_idx
    ON denial_event(clinic_id, payer_id, received_date DESC, id DESC);

CREATE TRIGGER denial_event_immutable
    BEFORE UPDATE OR DELETE ON denial_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- 4. Recovery Cases (recovery_case)
--    Separate DenialCaseStatus and RecoveryOutcome state machines; sourced deadline provenance.
CREATE TABLE recovery_case (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    payer_id uuid NOT NULL,
    primary_denial_event_id uuid NOT NULL,
    case_status varchar(40) NOT NULL CHECK (case_status IN (
        'NEW',
        'ANALYSIS_VERIFICATION',
        'ACTION_REQUIRED',
        'APPEAL_DRAFT',
        'AWAITING_APPROVAL',
        'APPROVED_FOR_SUBMISSION',
        'SUBMITTED_TO_PAYER',
        'PAYER_REVIEW',
        'ESCALATED',
        'CLOSED'
    )),
    recovery_outcome varchar(40) NOT NULL CHECK (recovery_outcome IN (
        'PENDING',
        'RECOVERED_FULL',
        'RECOVERED_PARTIAL',
        'UPHELD_WRITTEN_OFF',
        'WITHDRAWN_CORRECTED_CLAIM'
    )),
    assigned_to_user_id uuid REFERENCES app_user(id),
    due_date date,
    due_date_basis varchar(32) NOT NULL CHECK (due_date_basis IN (
        'APPROVED_SOURCED',
        'STAFF_CONFIRMED',
        'UNKNOWN'
    )),
    due_date_rule_version_id uuid,
    due_date_source_note varchar(240),
    estimated_recoverable_amount numeric(20,6) CHECK (
        estimated_recoverable_amount IS NULL OR estimated_recoverable_amount >= 0
    ),
    estimate_basis varchar(32) NOT NULL CHECK (estimate_basis IN (
        'UNESTIMATED',
        'SOURCED_RULE',
        'STAFF_CONFIRMED'
    )),
    estimate_note varchar(240),
    closure_reason varchar(240),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, primary_denial_event_id),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id),
    FOREIGN KEY (clinic_id, claim_id, revision_id) REFERENCES claim_revision(clinic_id, claim_id, id),
    FOREIGN KEY (clinic_id, payer_id) REFERENCES payer(clinic_id, id),
    FOREIGN KEY (clinic_id, primary_denial_event_id) REFERENCES denial_event(clinic_id, id),
    FOREIGN KEY (clinic_id, due_date_rule_version_id) REFERENCES payer_rule_version(clinic_id, id),
    CHECK (
        (due_date_basis = 'UNKNOWN' AND due_date IS NULL AND due_date_rule_version_id IS NULL)
        OR (due_date_basis = 'APPROVED_SOURCED' AND due_date IS NOT NULL AND due_date_rule_version_id IS NOT NULL)
        OR (due_date_basis = 'STAFF_CONFIRMED' AND due_date IS NOT NULL AND due_date_source_note IS NOT NULL AND btrim(due_date_source_note) <> '')
    ),
    CHECK (
        (estimate_basis = 'UNESTIMATED' AND estimated_recoverable_amount IS NULL)
        OR (estimate_basis IN ('SOURCED_RULE', 'STAFF_CONFIRMED') AND estimated_recoverable_amount IS NOT NULL AND estimate_note IS NOT NULL AND btrim(estimate_note) <> '')
    ),
    CHECK (
        (case_status = 'CLOSED') = (recovery_outcome <> 'PENDING' AND closure_reason IS NOT NULL AND btrim(closure_reason) <> '')
    )
);
CREATE INDEX recovery_case_queue_idx
    ON recovery_case(clinic_id, case_status, due_date ASC NULLS LAST, updated_at DESC, id DESC);
CREATE INDEX recovery_case_claim_idx
    ON recovery_case(clinic_id, claim_id, id DESC);

-- 5. Recovery Tasks (recovery_task)
CREATE TABLE recovery_task (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    recovery_case_id uuid NOT NULL,
    task_kind varchar(40) NOT NULL CHECK (task_kind IN (
        'VERIFY_SOURCE_WORDING',
        'COLLECT_SUPPORTING_DOCUMENTS',
        'RECONCILE_EXPECTED_AMOUNT',
        'REQUEST_CLINICAL_CLARIFICATION',
        'PREPARE_APPEAL_DRAFT',
        'MANAGER_ESCALATION_REVIEW',
        'CUSTOM_FOLLOW_UP'
    )),
    title varchar(160) NOT NULL CHECK (btrim(title) <> ''),
    description varchar(500),
    required_document_key varchar(80),
    satisfied_document_id uuid,
    status varchar(24) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    assigned_to_user_id uuid REFERENCES app_user(id),
    due_date date,
    resolution_note varchar(240),
    completed_by uuid REFERENCES app_user(id),
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    FOREIGN KEY (clinic_id, recovery_case_id) REFERENCES recovery_case(clinic_id, id),
    FOREIGN KEY (clinic_id, satisfied_document_id) REFERENCES document(clinic_id, id),
    CHECK ((status = 'COMPLETED') = (completed_by IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX recovery_task_case_idx
    ON recovery_task(clinic_id, recovery_case_id, created_at ASC, id ASC);

-- 6. Versioned Appeal Drafts (appeal_draft_version)
CREATE TABLE appeal_draft_version (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    recovery_case_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number >= 1),
    status varchar(32) NOT NULL CHECK (status IN (
        'DRAFT',
        'PENDING_APPROVAL',
        'APPROVED',
        'REJECTED',
        'SUPERSEDED',
        'SUBMITTED'
    )),
    locale varchar(5) NOT NULL CHECK (locale IN ('fr-SN', 'en')),
    subject_line varchar(200) NOT NULL CHECK (btrim(subject_line) <> ''),
    body_text varchar(8000) NOT NULL CHECK (btrim(body_text) <> ''),
    factual_basis_summary varchar(1000) NOT NULL CHECK (btrim(factual_basis_summary) <> ''),
    cited_evidence_ids_json text NOT NULL DEFAULT '[]',
    unresolved_placeholder_count integer NOT NULL CHECK (unresolved_placeholder_count >= 0),
    approved_by uuid REFERENCES app_user(id),
    approved_at timestamptz,
    approval_note varchar(240),
    rejected_by uuid REFERENCES app_user(id),
    rejected_at timestamptz,
    rejection_reason varchar(240),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, recovery_case_id, version_number),
    FOREIGN KEY (clinic_id, recovery_case_id) REFERENCES recovery_case(clinic_id, id),
    CHECK (
        status NOT IN ('APPROVED', 'SUBMITTED')
        OR (unresolved_placeholder_count = 0 AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CHECK (
        status <> 'REJECTED'
        OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> '')
    )
);
CREATE INDEX appeal_draft_case_idx
    ON appeal_draft_version(clinic_id, recovery_case_id, version_number DESC, id DESC);

CREATE OR REPLACE FUNCTION block_appeal_draft_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'APPEAL_DRAFT_VERSION_IMMUTABLE';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.clinic_id <> OLD.clinic_id
        OR NEW.recovery_case_id <> OLD.recovery_case_id
        OR NEW.version_number <> OLD.version_number
        OR NEW.locale <> OLD.locale
        OR NEW.subject_line <> OLD.subject_line
        OR NEW.body_text <> OLD.body_text
        OR NEW.factual_basis_summary <> OLD.factual_basis_summary
        OR NEW.cited_evidence_ids_json <> OLD.cited_evidence_ids_json
        OR NEW.unresolved_placeholder_count <> OLD.unresolved_placeholder_count
        OR NEW.created_by <> OLD.created_by
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'APPEAL_DRAFT_VERSION_IMMUTABLE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER appeal_draft_version_immutable
    BEFORE UPDATE OR DELETE ON appeal_draft_version
    FOR EACH ROW EXECUTE FUNCTION block_appeal_draft_mutation();

-- 7. Append-only External Appeal Submission Records (appeal_submission_record)
CREATE TABLE appeal_submission_record (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    recovery_case_id uuid NOT NULL,
    appeal_draft_version_id uuid NOT NULL,
    channel_code varchar(60) NOT NULL CHECK (btrim(channel_code) <> ''),
    external_submission_ref varchar(120) NOT NULL CHECK (btrim(external_submission_ref) <> ''),
    occurred_on date NOT NULL,
    occurred_at timestamptz,
    occurrence_precision varchar(16) NOT NULL CHECK (occurrence_precision IN ('DATE', 'INSTANT')),
    evidence_document_id uuid,
    notes varchar(240),
    idempotency_key varchar(120) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash char(64) NOT NULL,
    recorded_by uuid NOT NULL REFERENCES app_user(id),
    recorded_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, recovery_case_id, idempotency_key),
    FOREIGN KEY (clinic_id, recovery_case_id) REFERENCES recovery_case(clinic_id, id),
    FOREIGN KEY (clinic_id, appeal_draft_version_id) REFERENCES appeal_draft_version(clinic_id, id),
    FOREIGN KEY (clinic_id, evidence_document_id) REFERENCES document(clinic_id, id),
    CHECK ((occurrence_precision = 'INSTANT') = (occurred_at IS NOT NULL))
);
CREATE INDEX appeal_submission_case_idx
    ON appeal_submission_record(clinic_id, recovery_case_id, recorded_at ASC, id ASC);

CREATE TRIGGER appeal_submission_record_immutable
    BEFORE UPDATE OR DELETE ON appeal_submission_record
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
