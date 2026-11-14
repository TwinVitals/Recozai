# Recozai — Domain Model, State Machines & Invariants

> **Scope**: Complete domain specification for Recozai, covering existing entities (`V1`–`V6`) and target denial-recovery entities (`V7+`), explicit separation of lifecycle state machines, exact `XOF` financial journal invariants, source-backed payer rules, and AI trust boundaries.

---

## 1. Core Domain Design Principles

1. **Tenant Scoping by Construction**: Every domain aggregate (except global `app_user` identity) carries a non-null `clinicId` (`UUID`) and is persisted with a composite `UNIQUE (clinic_id, id)` constraint so all child tables reference `(clinic_id, parent_id)` via composite foreign keys.
2. **Five Distinct Lifecycle State Machines**: Claim lifecycle, Denial Case lifecycle, Appeal lifecycle, Payment/Ledger status, and Recovery Outcome are modeled as **separate, orthogonal state machines** and never collapsed into a single status column.
3. **Immutability of Historical & Financial Facts**: Submitted claim revisions (`claim_revision`, `claim_line`), external submission events (`claim_submission`), extracted/corrected evidence (`evidence_version`), financial ledger entries (`financial_journal_entry`), appeal submission records (`appeal_submission_record`), and audit events (`audit_event`) are append-only and protected by PostgreSQL `forbid_mutation()` triggers.
4. **Exact Monetary Representation (`sn.recozai.shared.Money`)**: Money is represented as `(BigDecimal amount, String currency, int scale)`. For `XOF` (West African CFA franc), `scale = 0`. Binary floating-point (`float`/`double`) is forbidden across Java, SQL (`NUMERIC(19, 4)`), JSON (`String` decimal serialization), and TypeScript.

---

## 2. Entity & Aggregate Relationship Overview

```text
clinic_settings (1)
 ├──< clinic_membership (N) >── app_user (1)
 ├──< payer (N)
 │     ├──< document_object [parent=PAYER] (N) ──< evidence_version (N)
 │     └──< payer_rule_version [DRAFT|APPROVED|SUPERSEDED] (N)
 ├──< denial_category (N)
 ├──< claim_import_batch (N) ──< claim_import_row (N)
 └──< claim_record (N)
       ├──< claim_draft_line (N)          [mutable only while claim_record.state = DRAFT]
       ├──< claim_revision [R1..Rn] (N)   [immutable; protected by forbid_mutation()]
       │     ├──< claim_line (N)          [immutable; protected by forbid_mutation()]
       │     └──< claim_submission (N)    [append-only; SUBMISSION | RESUBMISSION | METADATA_CORRECTION]
       ├──< document_object [parent=CLAIM] (N)
       │     ├──< evidence_version (N)
       │     └──< document_field_extraction (N)
       ├──< financial_journal_entry (N)   [append-only ledger; protected by forbid_mutation()]
       ├──< denial_event (N)              [append-only payer rejection / discrepancy notices]
       └──< recovery_case (N)
             ├──< recovery_task (N)
             ├──< ai_suggestion_record (N)
             ├──< appeal_draft_version (N)
             │     └──< appeal_submission_record (N) [append-only; requires APPROVED_FOR_SUBMISSION]
             └──< payer_response_record (N)
```

---

## 3. Orthogonal State Machines & Lifecycles

Recozai explicitly separates **five** status dimensions so billing teams never confuse an internal appeal draft with an actual payer submission or an estimated recovery with confirmed cash/credit.

### 3.1 Claim Status (`ClaimState` — `claim_record.state`)
Tracks the administrative state of the claim header and its numbered revisions (`R1..Rn`):

```text
[ DRAFT ] ──(register R1)──► [ REGISTERED ] ──(record SUBMISSION on R1)──► [ SUBMITTED ]
    │                               │                                            │
    ├──(voidClaim)──► [ VOID ]      ├──(voidClaim: only if 0 submissions)────────┤
    │                               │                                            │
    └──(archive)────► [ ARCHIVED ] ◄┴─────────────────(archive)──────────────────┘
                           │
                           └──(reopen: restores prior non-archived state)────────►
```
- **Invariants** (implemented in `ClaimDomain.java`):
  - Draft lines (`claim_draft_line`) exist only in `DRAFT`; registration deletes draft lines and writes immutable `claim_revision` `R1` + `claim_line` rows.
  - Amending a `REGISTERED` or `SUBMITTED` claim requires `BILLING_MANAGER` and creates `R(n+1)` with a non-blank `change_reason` without mutating `R1..Rn`.
  - `VOID` is terminal and forbidden once any `claim_submission` row exists on any revision (`CLAIM_VOID_NOT_ALLOWED`).

### 3.2 Denial Case Status (`DenialCaseStatus` — `recovery_case.case_status`)
Tracks the operational workflow of investigating and resolving a rejected or underpaid claim revision:

```text
[ NEW ]
  │
  ▼ (start review / attach evidence / run extraction)
[ ANALYSIS_VERIFICATION ]
  │
  ├──(missing evidence / staff task needed)──► [ ACTION_REQUIRED ]
  │                                                   │
  ◄───────────────(evidence verified)─────────────────┘
  │
  ├──(prepare reconsideration/appeal)────────► [ APPEAL_DRAFT ]
  │                                                   │
  │                                                   ▼ (submit draft for manager review)
  │                                            [ AWAITING_APPROVAL ]
  │                                                   │
  │                                                   ▼ (BILLING_MANAGER approves appeal version)
  │                                            [ APPROVED_FOR_SUBMISSION ]
  │                                                   │
  │                                                   ▼ (record external submission + evidence)
  │                                            [ SUBMITTED_TO_PAYER ]
  │                                                   │
  │                                                   ▼ (payer acknowledges / under review)
  │                                            [ PAYER_REVIEW ]
  │                                                   │
  ├──(escalate to management / convention)───────────►├──► [ ESCALATED ]
  │                                                   │         │
  └──────────────(record terminal outcome & close)────┴─────────┴──► [ CLOSED ]
```
- **Invariants**:
  - A `recovery_case` cannot enter `APPROVED_FOR_SUBMISSION` unless at least one `appeal_draft_version` has `status = 'APPROVED'`.
  - A `recovery_case` cannot enter `SUBMITTED_TO_PAYER` unless an `appeal_submission_record` exists with a non-blank `channel_code`, `occurred_on`, and either an `external_submission_ref` or `evidence_document_id` (`CLEAN` document).

### 3.3 Appeal Status (`AppealStatus` — `appeal_draft_version.status`)
Tracks each versioned bilingual (`fr-SN` / `en`) appeal draft within a recovery case:

| Status | Meaning & Transition Rule |
|---|---|
| `DRAFT` | Editable by `BILLING_STAFF` or `BILLING_MANAGER` (whether human-authored or initialized from an accepted AI suggestion). Cannot be marked submitted to a payer. |
| `PENDING_APPROVAL` | Frozen for review; waiting for `BILLING_MANAGER` decision. |
| `APPROVED` | Approved by `BILLING_MANAGER` (`approved_by`, `approved_at` recorded). Unresolved `[PLACEHOLDER]` markers in the body block approval (`UNRESOLVED_APPEAL_PLACEHOLDERS`). Approving version `Vn` automatically marks any prior `APPROVED` version on the same case as `SUPERSEDED`. |
| `REJECTED` | Rejected by `BILLING_MANAGER` with mandatory `review_note`; staff may create `V(n+1)` as `DRAFT`. |
| `SUPERSEDED` | Replaced by a newer `draft` or `approved` version; retained immutable for audit history. |
| `SUBMITTED` | Linked to an append-only `appeal_submission_record` confirming external delivery to the payer. |

### 3.4 Payment Status (`ClaimPaymentStatus` — derived from `financial_journal_entry`)
Tracks verified financial settlement of the active claim revision (never updated by AI or unconfirmed appeal drafts):

| Status | Mathematical Definition (in Claim's Frozen Currency & Scale, e.g., `XOF`) |
|---|---|
| `UNPAID_PENDING` | `submittedAmount > 0` and `deniedAmount == 0` and `paidAmount == 0` and `writtenOffAmount == 0` |
| `DENIED_UNPAID` | `deniedAmount == submittedAmount` and `recoveredAmount == 0` and `paidAmount == 0` |
| `PARTIALLY_PAID_DISPUTED` | `(initialPaidAmount + recoveredAmount) > 0` and `openDisputedAmount > 0` |
| `RECOVERED_PARTIAL` | `recoveredAmount > 0` and `openDisputedAmount == 0` and `writtenOffAmount > 0` |
| `PAID_OR_RECOVERED_FULL` | `(initialPaidAmount + recoveredAmount + contractualAdjustmentAmount) == submittedAmount` and `openDisputedAmount == 0` |
| `WRITTEN_OFF` | `writtenOffAmount == deniedAmount` and `recoveredAmount == 0` |

### 3.5 Recovery Outcome (`RecoveryOutcome` — `recovery_case.recovery_outcome`)
Tracks the business resolution of a `recovery_case`:
- `PENDING` (default while case is open)
- `RECOVERED_FULL` (100% of disputed/denied amount on the case recovered via confirmed payer payment/credit)
- `RECOVERED_PARTIAL` (Confirmed payer recovery `> 0` and `< disputedAmount`, with remainder adjusted or written off by `BILLING_MANAGER`)
- `UPHELD_WRITTEN_OFF` (Payer denial upheld or administrative write-off approved by `BILLING_MANAGER` with mandatory reason)
- `WITHDRAWN_CORRECTED_CLAIM` (Closed because claim was amended via a new `claim_revision` `R(n+1)` or corrected submission metadata)

---

## 4. Detailed Domain Entities & Invariants

### 4.1 Batch Claim Import (`B06I` — `sn.recozai.claim`)
- **`claim_import_batch`**:
  - Fields: `id`, `clinic_id`, `payer_id`, `source_document_id` (references `CLEAN` uploaded CSV/XLSX `document_object` or SHA-256 file digest), `idempotency_key`, `request_hash`, `mapping_profile_json`, `status` (`STAGED`, `COMMITTED`, `FAILED`), `total_rows`, `valid_rows`, `duplicate_rows`, `rejected_rows`, `created_by`, `created_at`, `committed_at`.
- **`claim_import_row`**:
  - Fields: `id`, `clinic_id`, `batch_id`, `row_number`, `external_reference`, `pseudonymous_patient_ref`, `source_date`, `raw_payload_json`, `validation_status` (`VALID`, `DUPLICATE_EXISTING`, `DUPLICATE_IN_FILE`, `INVALID`), `error_code`, `error_detail`, `created_claim_id`.
- **Invariants**:
  - Staging (`POST /api/v1/claim-imports/preview`) parses and validates every row without creating claims.
  - Commit (`POST /api/v1/claim-imports/{batchId}/commit` with `Idempotency-Key`) creates `REGISTERED` claims (`R1`) for `VALID` rows in a transaction and retains `claim_import_row` error diagnostics for rejected/duplicate rows.

### 4.2 Immutable Financial Journal (`B07` — `sn.recozai.ledger`)
- **`financial_journal_entry`**:
  - Fields: `id`, `clinic_id`, `claim_id`, `revision_id`, `recovery_case_id` (nullable), `entry_number` (`1..N` per claim), `entry_kind` (`CLAIM_SUBMITTED`, `INITIAL_PAYER_PAYMENT`, `CONTRACTUAL_ADJUSTMENT`, `DENIAL_RECORDED`, `RECOVERY_PAYMENT`, `WRITE_OFF`, `REVERSAL`), `amount` (`NUMERIC(19, 4) NOT NULL`, strictly `> 0`), `currency` (`VARCHAR(3)`), `currency_scale` (`SMALLINT`), `reverses_entry_id` (nullable FK to prior `financial_journal_entry`), `evidence_document_id` (nullable FK to `CLEAN` `document_object`), `external_reference`, `occurred_on`, `reason` (`VARCHAR(500) NOT NULL`), `recorded_by`, `recorded_at`, `idempotency_key`, `request_hash`.
- **Financial Invariants**:
  1. **Append-Only**: Protected by `forbid_mutation()` trigger; corrections require inserting a `REVERSAL` entry referencing `reverses_entry_id` (an entry can be reversed at most once: `UNIQUE (clinic_id, reverses_entry_id)`).
  2. **Currency & Scale Match**: Every entry on a claim must match `claim_record.currency` and `claim_record.currency_scale` (`INVALID_MONETARY_SCALE` / `CURRENCY_MISMATCH` otherwise).
  3. **Conservation of Claim Value**:
     - For any `revision_id`: `netDenied + netInitialPaid + netContractualAdjustment <= revision.submitted_amount`.
     - For any `recovery_case_id`: `netRecovered + netWrittenOff <= case.disputed_amount`.
     - `openDisputedAmount = netDenied - netRecovered - netWrittenOff >= 0`.
  4. **Estimate vs. Ledger Separation**: `recovery_case.estimated_recoverable_amount` is stored on `recovery_case` with an explicit `estimate_basis` (`RULE_BASED_ESTIMATE | AI_SUGGESTED_ESTIMATE | STAFF_ESTIMATE`) and **never** creates a `financial_journal_entry`.

### 4.3 Denial & Discrepancy Intake (`B09` — `sn.recozai.denial`)
- **`denial_event`**:
  - Fields: `id`, `clinic_id`, `claim_id`, `revision_id`, `claim_line_id` (nullable — supports header-level or line-level rejection), `payer_id`, `event_type` (`FULL_REJECTION`, `PARTIAL_REJECTION`, `TARIFF_DISCREPANCY`, `INFORMATION_REQUEST`), `source_reason_code` (`VARCHAR(80)` nullable), `original_reason_text` (`VARCHAR(2000) NOT NULL` — verbatim payer wording), `normalized_category_id` (nullable FK to `denial_category`), `normalization_basis` (`UNMAPPED`, `APPROVED_TERMINOLOGY_RULE`, `STAFF_CLASSIFIED`, `AI_ACCEPTED_BY_STAFF`), `normalization_rule_version_id` (nullable FK to `payer_rule_version`), `denied_amount` (`NUMERIC(19, 4) NOT NULL`), `currency`, `currency_scale`, `notice_date` (`DATE NOT NULL`), `received_date` (`DATE NOT NULL`), `source_document_id` (nullable FK to `CLEAN` `document_object`), `supersedes_id` (nullable self-FK for metadata correction), `recorded_by`, `recorded_at`, `idempotency_key`, `request_hash`.
- **Invariants**:
  - `original_reason_text` is immutable and never overwritten by normalization or translation.
  - Recording a `denial_event` atomically appends a `DENIAL_RECORDED` entry in `financial_journal_entry` and can open or link to a `recovery_case`.

### 4.4 Recovery Case, Tasks & Appeals (`B10` — `sn.recozai.recovery`)
- **`recovery_case`**:
  - Fields: `id`, `clinic_id`, `claim_id`, `revision_id`, `payer_id`, `primary_denial_event_id`, `case_reference` (`VARCHAR(80)` unique per clinic), `case_status` (`DenialCaseStatus`), `recovery_outcome` (`RecoveryOutcome`), `priority` (`LOW`, `MEDIUM`, `HIGH`, `URGENT`), `assigned_to_user_id` (nullable), `disputed_amount`, `estimated_recoverable_amount` (nullable), `estimate_basis` (`NONE`, `STAFF_ESTIMATE`, `RULE_BASED_ESTIMATE`, `AI_ACCEPTED_ESTIMATE`), `due_date` (`DATE` nullable), `due_date_basis` (`UNKNOWN`, `APPROVED_SOURCED`, `STAFF_CONFIRMED`), `due_date_rule_version_id` (nullable FK to `payer_rule_version`), `due_date_source_note` (nullable), `version` (`BIGINT`), `created_by`, `created_at`, `updated_at`, `closed_at`.
- **`recovery_task`**:
  - Fields: `id`, `clinic_id`, `recovery_case_id`, `task_type` (`COLLECT_DOCUMENT`, `VERIFY_EXTRACTION`, `REVIEW_PAYER_RULE`, `DRAFT_APPEAL`, `APPROVE_APPEAL`, `SUBMIT_APPEAL`, `FOLLOW_UP_PAYER`), `title_fr`, `title_en`, `required_document_key` (nullable), `fulfilled_document_id` (nullable FK to `CLEAN` `document_object`), `due_date` (nullable), `status` (`OPEN`, `COMPLETED`, `WAIVED`), `completed_by`, `completed_at`, `waive_reason` (nullable), `version`.
- **`appeal_draft_version`**:
  - Fields: `id`, `clinic_id`, `recovery_case_id`, `version_number` (`1..N`), `locale` (`fr-SN` | `en`), `subject` (`VARCHAR(240)`), `body_markdown` (`TEXT NOT NULL`), `source_basis_json` (JSON array of cited `evidence_version_id`, `payer_rule_version_id`, and `claim_revision_id` references), `generated_from_ai_suggestion_id` (nullable), `status` (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `SUPERSEDED`, `SUBMITTED`), `created_by`, `created_at`, `reviewed_by`, `reviewed_at`, `review_note`, `version`.
- **`appeal_submission_record`** (Append-only, protected by `forbid_mutation()`):
  - Fields: `id`, `clinic_id`, `recovery_case_id`, `appeal_version_id` (must reference an `APPROVED` `appeal_draft_version`), `channel_code` (e.g., `BORDEREAU_PAPIER`, `COURRIER_RECOMMANDE`, `PORTAIL_IPM`, `EMAIL`), `external_submission_ref` (`VARCHAR(120)`), `occurred_on` (`DATE NOT NULL`), `occurred_at` (`TIMESTAMPTZ` nullable), `occurrence_precision` (`DATE` | `INSTANT`), `evidence_document_id` (nullable FK to `CLEAN` `document_object`), `submitted_by`, `recorded_at`, `idempotency_key`, `request_hash`.

### 4.5 Staged Field Extractions & AI Advisory Records (`B08` extension & `B11` — `sn.recozai.document` / `sn.recozai.ai`)
- **`document_field_extraction`**:
  - Fields: `id`, `clinic_id`, `document_id`, `evidence_version_id`, `field_key` (`CLAIM_REF`, `PAYER_CODE`, `REJECTION_CODE`, `REJECTION_REASON_TEXT`, `DENIED_AMOUNT`, `NOTICE_DATE`, `MISSING_DOCUMENT_KEY`, `DEADLINE_DAYS`), `extracted_value` (`VARCHAR(1000)`), `confidence_bps` (`INTEGER` `0..10000`, e.g., `8500` = `85.00%`), `page_number`, `region_label`, `verification_status` (`UNVERIFIED`, `HUMAN_CONFIRMED`, `HUMAN_CORRECTED`, `REJECTED`), `verified_value` (nullable), `verified_by`, `verified_at`.
  - **Invariant**: Staged extractions **never** overwrite `claim_record`, `denial_event`, or `payer_rule_version` fields until a human explicitly confirms or applies them.
- **`ai_invocation_log`** (Append-only telemetry):
  - Fields: `id`, `clinic_id`, `actor_user_id`, `recovery_case_id` (nullable), `document_id` (nullable), `task_type`, `provider_code`, `model_id`, `prompt_version`, `locale`, `status` (`SUCCEEDED`, `SCHEMA_REJECTED`, `TIMEOUT`, `PROVIDER_ERROR`, `BLOCKED_PRIVACY_GATE`), `latency_ms`, `input_tokens`, `output_tokens`, `correlation_id`, `created_at`.
- **`ai_suggestion_record`**:
  - Fields: `id`, `clinic_id`, `recovery_case_id`, `invocation_id`, `suggestion_kind` (`CLASSIFICATION`, `RECOVERY_CHECKLIST`, `APPEAL_DRAFT`, `CASE_QA`), `structured_payload_json`, `confidence_bps`, `cited_rule_version_ids_json`, `cited_evidence_version_ids_json`, `requires_human_verification` (`BOOLEAN NOT NULL DEFAULT TRUE`), `review_status` (`PENDING_REVIEW`, `ACCEPTED`, `EDITED_AND_ACCEPTED`, `REJECTED`), `reviewed_by`, `reviewed_at`, `created_at`.
