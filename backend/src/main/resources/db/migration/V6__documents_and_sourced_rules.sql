-- B08: Document management, quarantine, extraction provenance, and sourced payer rule approval (FR-008, FR-003, AC-02, AC-07, AR-10, AR-11)

CREATE TABLE document (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL REFERENCES clinic(id),
    claim_id uuid,
    payer_id uuid,
    storage_key varchar(160) NOT NULL UNIQUE,
    source_name varchar(240) NOT NULL,
    mime_type varchar(80) NOT NULL CHECK (mime_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    byte_count bigint NOT NULL CHECK (byte_count > 0 AND byte_count <= 20971520),
    sha256_hash varchar(64) NOT NULL,
    quarantine_status varchar(30) NOT NULL CHECK (quarantine_status IN ('QUARANTINED', 'CLEAN', 'REJECTED')),
    scan_status varchar(40) NOT NULL CHECK (scan_status IN ('SCAN_PENDING', 'CLEAN', 'SCANNER_UNAVAILABLE', 'MALICIOUS_DETECTED', 'FORMAT_INVALID')),
    scan_detail varchar(160),
    page_count integer CHECK (page_count IS NULL OR (page_count >= 1 AND page_count <= 100)),
    image_width integer CHECK (image_width IS NULL OR image_width >= 1),
    image_height integer CHECK (image_height IS NULL OR image_height >= 1),
    version bigint NOT NULL DEFAULT 0,
    uploaded_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    FOREIGN KEY (clinic_id, claim_id) REFERENCES claim(clinic_id, id),
    FOREIGN KEY (clinic_id, payer_id) REFERENCES payer(clinic_id, id),
    CHECK ((claim_id IS NOT NULL AND payer_id IS NULL) OR (claim_id IS NULL AND payer_id IS NOT NULL)),
    CHECK (image_width IS NULL OR image_height IS NULL OR (CAST(image_width AS bigint) * CAST(image_height AS bigint) <= 25000000))
);
CREATE INDEX document_claim_idx ON document(clinic_id, claim_id, created_at DESC, id) WHERE claim_id IS NOT NULL;
CREATE INDEX document_payer_idx ON document(clinic_id, payer_id, created_at DESC, id) WHERE payer_id IS NOT NULL;

CREATE TABLE evidence_version (
    id uuid PRIMARY KEY,
    clinic_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number >= 1),
    page_number integer NOT NULL CHECK (page_number >= 1),
    region_label varchar(120) NOT NULL,
    extracted_text text NOT NULL,
    extraction_method varchar(60) NOT NULL,
    quality_flag varchar(40) NOT NULL CHECK (quality_flag IN ('EXTRACTED', 'NEEDS_OCR_OR_REVIEW', 'HUMAN_VERIFIED')),
    reviewer_correction boolean NOT NULL DEFAULT false,
    correction_reason varchar(240),
    supersedes_id uuid UNIQUE,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (clinic_id, id),
    UNIQUE (clinic_id, document_id, id),
    UNIQUE (clinic_id, document_id, version_number),
    FOREIGN KEY (clinic_id, document_id) REFERENCES document(clinic_id, id),
    FOREIGN KEY (clinic_id, document_id, supersedes_id)
        REFERENCES evidence_version(clinic_id, document_id, id),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (
        (reviewer_correction = false AND supersedes_id IS NULL AND correction_reason IS NULL)
        OR (reviewer_correction = true AND supersedes_id IS NOT NULL AND correction_reason IS NOT NULL)
    )
);
CREATE INDEX evidence_version_doc_idx ON evidence_version(clinic_id, document_id, version_number ASC, id);

CREATE OR REPLACE FUNCTION prevent_evidence_version_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Evidence versions are immutable and cannot be updated or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_version_immutable
    BEFORE UPDATE OR DELETE ON evidence_version
    FOR EACH ROW EXECUTE FUNCTION prevent_evidence_version_mutation();

-- Wire final composite foreign keys for evidence documents on payer rules, claim revisions, and claim submissions (AR-10, AR-11)
ALTER TABLE payer_rule_version
    ADD CONSTRAINT payer_rule_version_source_doc_fk
    FOREIGN KEY (clinic_id, source_document_id)
    REFERENCES document(clinic_id, id);

CREATE UNIQUE INDEX payer_rule_version_one_approved_per_kind
    ON payer_rule_version(clinic_id, payer_id, rule_kind)
    WHERE state = 'APPROVED';

ALTER TABLE claim_revision
    ADD CONSTRAINT claim_revision_evidence_doc_fk
    FOREIGN KEY (clinic_id, evidence_document_id)
    REFERENCES document(clinic_id, id);

ALTER TABLE claim_submission
    ADD CONSTRAINT claim_submission_evidence_doc_fk
    FOREIGN KEY (clinic_id, evidence_document_id)
    REFERENCES document(clinic_id, id);
