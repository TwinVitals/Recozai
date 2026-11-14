# ADR-0004: Staged Idempotent Claim Import and Evidence-Backed Manual Channel Submission Tracking

- **Status**: Accepted
- **Context**:
  Senegal clinics use varied billing/invoicing exports (CSV/XLSX) and submit claims and reconsideration appeals across mixed channels (physical signed *bordereaux*, registered mail, email, or payer web portals) rather than US-style X12 837/835 EDI APIs.
- **Decision**:
  1. Implement `B06I` claim intake as a two-phase staged workflow (`claim_import_batch` + `claim_import_row`):
     - **Phase A (Preview/Stage)**: Parse CSV/XLSX using a clinic-configurable column mapping profile, validate monetary scale (`XOF` integer scale), dates, and payer codes, and classify each row as `VALID`, `DUPLICATE_EXISTING`, `DUPLICATE_IN_FILE`, or `INVALID` without mutating `claim_record`.
     - **Phase B (Idempotent Commit)**: Commit `VALID` rows into `REGISTERED` claims (`R1`) using `Idempotency-Key` + `request_hash`, preserving row-level rejection diagnostics for downloadable staff review.
  2. Model appeal delivery (`appeal_submission_record`) after `claim_submission`: an appeal is never marked `SUBMITTED_TO_PAYER` merely because an appeal draft was generated or approved. Staff must record the actual delivery channel (`channel_code`), date/instant, and external reference or uploaded `CLEAN` receipt document (`evidence_document_id`).
- **Consequences**:
  - Clinics can onboard immediately using their existing spreadsheet exports and paper/portal payer workflows without waiting for bespoke payer API integrations.
