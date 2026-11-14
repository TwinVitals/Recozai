CREATE TABLE payer (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    code varchar(60) NOT NULL,
    type_code varchar(60) NOT NULL,
    original_name varchar(160) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, code)
);
CREATE INDEX payer_clinic ON payer(clinic_id, code, id);

CREATE TABLE denial_category (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    category_key varchar(80) NOT NULL,
    label_fr varchar(160) NOT NULL,
    label_en varchar(160) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, category_key)
);
CREATE INDEX denial_category_clinic ON denial_category(clinic_id, category_key, id);

CREATE TABLE payer_rule_version (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    payer_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number >= 1),
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT','APPROVED','SUPERSEDED')),
    rule_kind varchar(40) NOT NULL CHECK (rule_kind IN
        ('TERMINOLOGY_MAPPING','REQUIRED_DOCUMENTS','DEADLINE_RULE','WORKFLOW_STEPS')),
    source_document_id uuid,
    approved_by uuid REFERENCES app_user(id),
    approved_at timestamptz,
    effective_from date,
    effective_to date,
    deadline_basis varchar(30) NOT NULL CHECK (deadline_basis IN ('NOT_APPLICABLE','UNKNOWN','PARAMETERIZED_DRAFT','APPROVED_SOURCED')),
    config_json text NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, payer_id, version_number),
    FOREIGN KEY (clinic_id, payer_id) REFERENCES payer(clinic_id, id),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CHECK (state = 'DRAFT' OR (source_document_id IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE INDEX payer_rule_version_payer ON payer_rule_version(clinic_id, payer_id, version_number DESC, id);
