# Recozai — Incremental Implementation Plan

> **Scope**: Phased, repository-specific delivery plan for evolving Recozai from its current state (`B01`–`B06`, `B08`) into a complete Senegal AI Denial Recovery Agent pilot without rewriting working modules.

---

## 1. Current Baseline & Guiding Rules

- **Already Complete & Verified in Code**:
  - `B01`–`B04`: Identity, opaque sessions, CSRF, rate limits, Ed25519 custodian grants, clinic settings, and immutable `audit_event` trail (`V1`–`V3`).
  - `B05`: Clinic-scoped payers, bilingual denial categories, and payer rule versions (`V4`).
  - `B06`: Manual claims, immutable revisions (`R1..Rn`), frozen currency/scale, and append-only external submission records (`V5`).
  - `B08`: Private document uploads, magic-byte check, fail-closed malware scan (`QUARANTINED`/`CLEAN`), native PDF text extraction (`evidence_version`), human evidence correction, and sourced payer rule approval (`V6`).
- **Delivery Principles**:
  - Every phase delivers a **working, tested vertical slice** preserving `ArchitectureTest` hexagonal boundaries.
  - Every database migration (`V7+`) is forward-safe, additive, and enforced by composite `(clinic_id, id)` foreign keys and `forbid_mutation()` triggers where appropriate.
  - No phase introduces microservices, external message brokers, or floating-point monetary types.

---

## 2. Phased Delivery Roadmap

### Phase 0 — Baseline Stabilization & Defect Remediation *(First Small Implementation Slice)*
- **Objective**: Fix the compile error and configuration/RBAC/i18n defects discovered during read-only inspection so `./mvnw verify` and frontend checks pass cleanly before adding new features.
- **Status Note (Prompt 03 Reinspection)**:
  - `ClaimService.ClinicPolicyReader`, `catalog.ts` translation keys, and Phase 1 PrimeNG UI updates are already present in the working tree.
  - Actual `V1`–`V6` migrations name the base tables `clinic`, `claim`, and `document` (with `numeric(20,6)` monetary columns and `<module>.adapter` controllers/stores enforced by `ArchitectureTest`), rather than `clinic_settings`, `claim_record`, and `document_object`.
  - Remaining Phase 0 remediations (`DocumentService.requireParentWriteAccess`, `application.yml` multipart limits, `frontend/nginx.conf` `/api/` body size) are completed alongside the Prompt 03 foundation slice (`V7__foundation_ledger_denials_recovery_and_appeals.sql` and `sn.recozai.recovery`).
- **Dependencies**: None.
- **Affected Files / Modules**:
  - `backend/src/main/java/sn/recozai/claim/application/ClaimService.java` (restore `public interface ClinicPolicyReader {` on line 15 — *already applied*).
  - `backend/src/main/java/sn/recozai/document/application/DocumentService.java` (enforce `current.requireClaimWrite()` for claim document mutations while preserving `requireClaimRead()` for downloads/reads).
  - `backend/src/main/resources/application.yml` (configure `spring.servlet.multipart.max-file-size: 20MB` and `max-request-size: 21MB`).
  - `frontend/nginx.conf` (raise `client_max_body_size` for `/api/` to `21m` while keeping a tight default elsewhere).
  - `frontend/src/app/core/catalog.ts`, `frontend/src/app/pages/claims.ts`, `frontend/src/app/pages/payers.ts`, `frontend/src/app/core/api.service.ts` (add missing `evidenceDocumentLabel` / `noDocumentSelected` keys, localize `"Superseded"`, localize deadline enum `<option>` labels, and run Prettier — *already applied*).
- **Acceptance Criteria**:
  1. `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B verify` passes 100% of unit, ArchUnit, and Testcontainers integration tests.
  2. `CLAIMS_REVIEWER` can view/download claim documents and evidence (`200 OK`) but receives `403 FORBIDDEN` when attempting to upload, retry scan, extract, or correct evidence on a claim.
  3. Frontend `tsc --noEmit` and `prettier --check "src/**/*.{ts,html,css}"` pass with zero warnings.
- **Rollback / Checkpoint Strategy**: Create initial Git commit checkpoint on `main` once all existing tests pass.

---

### Phase 1 — Healthcare Operations UX Redesign & Bilingual Foundation (Prompt 02 & Prompt 06 alignment)
- **Objective**: Evolve the Angular 22 frontend from developer-utility forms into a calm, high-trust healthcare revenue-recovery workspace with complete `fr-SN` / `en` parity, Reactive Forms, accessible status badges (never relying on color alone), loading/empty/error/permission states, and navigation ready for denial cases, documents, and analytics.
- **Dependencies**: Phase 0.
- **Affected Files / Modules**:
  - `frontend/src/styles.css` (semantic design tokens, typography, high-contrast status chips, provenance badges for `VERIFIED`, `EXTRACTED`, `AI_ASSISTIVE_DRAFT`, `QUARANTINED`, responsive split-pane workspace layout).
  - `frontend/src/app/app.ts`, `frontend/src/app/app.routes.ts` (navigation shell, clinic/locale switcher, role-aware navigation items).
  - `frontend/src/app/pages/dashboard.ts`, `payers.ts`, `claims.ts`, `settings.ts` (refactor forms to typed `ReactiveFormsModule`, add empty/loading/error/permission states, and complete bilingual translations in `catalog.ts`).
- **Acceptance Criteria**:
  1. Zero hardcoded user-facing strings or raw backend enum codes in templates.
  2. Keyboard navigation, focus management, ARIA live regions for error/status banners, and non-color status icons verified in unit and E2E specs.
  3. Existing `B01`–`B06` and `B08` journeys continue to pass without regression.
- **Rollback / Checkpoint Strategy**: Frontend-only change set; revertible via Git checkpoint without database impact.

---

### Phase 2 — Immutable Financial Journal (`B07`) & Batch Claim Import (`B06I`) (Prompt 03 & Prompt 04 part 1)
- **Objective**: Introduce the append-only `XOF` financial ledger (`sn.recozai.ledger`) and configurable CSV/XLSX claim batch import (`sn.recozai.claim`).
- **Dependencies**: Phase 0.
- **Affected Files / Modules**:
  - `backend/src/main/resources/db/migration/V7__financial_journal_and_claim_imports.sql` (`financial_journal_entry` with `forbid_mutation()` trigger, `claim_import_batch`, `claim_import_row`).
  - `backend/src/main/java/sn/recozai/ledger/{domain,application,adapter,api}/*`.
  - `backend/src/main/java/sn/recozai/claim/{domain,application,adapter,api}/*` (hook claim submission to record initial `CLAIM_SUBMITTED` ledger entry; add CSV/XLSX import preview and idempotent commit).
  - `frontend/src/app/pages/claims.ts` (import preview/mapping wizard, duplicate/rejected-row diagnostic table, and claim ledger balance view).
- **Acceptance Criteria**:
  1. `financial_journal_entry` rejects `UPDATE`/`DELETE` at the PostgreSQL trigger level; corrections require `REVERSAL` entries.
  2. Ledger enforces `netDenied + netInitialPaid + netContractualAdjustment <= submittedAmount` and exact `claim_record.currency` / `currency_scale` matching.
  3. CSV/XLSX import preview validates rows, detects in-file and existing duplicates, and commits valid rows idempotently via `Idempotency-Key`.
- **Rollback / Checkpoint Strategy**: Forward-safe additive tables in `V7`; if rolled back before production, restore pre-`V7` snapshot under `RESTORE_GATE=CLOSED`.

---

### Phase 3 — Denial Intake (`B09`), Recovery Cases, Sourced Deadlines & Appeal Workflow (`B10`) (Prompt 04 part 2)
- **Objective**: Deliver the end-to-end human denial-recovery vertical slice: recording payer rejections/discrepancies (`denial_event`), managing `recovery_case` and `recovery_task` queues, computing deadlines strictly from `APPROVED_SOURCED` rules (or `STAFF_CONFIRMED` input), authoring bilingual `appeal_draft_version` records, enforcing `BILLING_MANAGER` approval, and recording external submission evidence and actual recovered payments.
- **Dependencies**: Phase 2 (`V7` financial journal).
- **Affected Files / Modules**:
  - `backend/src/main/resources/db/migration/V8__denials_recovery_cases_and_appeals.sql` (`denial_event`, `recovery_case`, `recovery_task`, `appeal_draft_version`, `appeal_submission_record`, `payer_response_record`, plus `document_object` parent extension for `recovery_case_id` and `document_field_extraction`).
  - `backend/src/main/java/sn/recozai/denial/{domain,application,adapter,api}/*`.
  - `backend/src/main/java/sn/recozai/recovery/{domain,application,adapter,api}/*`.
  - `backend/src/main/java/sn/recozai/web/RecoveryController.java`.
  - `frontend/src/app/pages/denials.ts`, `denial-detail.ts` (denial work queue, split-pane case detail, original vs. normalized reason display, evidence/extraction verifier, task checklist, bilingual appeal editor, manager approval action, submission & recovery modals).
- **Acceptance Criteria**:
  1. Separate state machines for `ClaimState`, `DenialCaseStatus`, `AppealStatus`, `ClaimPaymentStatus`, and `RecoveryOutcome` are enforced and tested.
  2. Verbatim `original_reason_text` is preserved alongside normalized `denial_category`.
  3. `recovery_case` cannot transition to `APPROVED_FOR_SUBMISSION` without `BILLING_MANAGER` approval on an `appeal_draft_version` free of unresolved placeholders, and cannot transition to `SUBMITTED_TO_PAYER` without an `appeal_submission_record` containing submission reference/evidence.
  4. Recording a partial or full payer recovery updates the append-only `financial_journal_entry` and derives `RECOVERED_PARTIAL` / `RECOVERED_FULL` accurately.
- **Rollback / Checkpoint Strategy**: Additive `V8` migration; git tag checkpoint after full integration test pass.

---

### Phase 4 — Bounded, Privacy-Gated AI Recovery Agent (`B11`) & Analytics/Exports (`B12`) (Prompt 05)
- **Objective**: Add the vendor-independent AI orchestration module (`sn.recozai.ai`) for staged document field extraction, denial classification, evidence-linked recovery recommendations, bilingual appeal drafting, and case Q&A, alongside operational recovery dashboards and audited exports (`sn.recozai.reporting`).
- **Dependencies**: Phase 3.
- **Affected Files / Modules**:
  - `backend/src/main/resources/db/migration/V9__ai_governance_and_reporting.sql` (`clinic_settings` AI privacy gate columns, `ai_invocation_log`, `ai_suggestion_record`).
  - `backend/src/main/java/sn/recozai/ai/{domain,application,adapter,api}/*` (`AiModelPort`, `DisabledAiAdapter`, `DeterministicMockAiAdapter`, `ConfigurableHttpAiAdapter`, prompt templates with version IDs, strict JSON schema validator, prompt-injection delimiter sanitizer).
  - `backend/src/main/java/sn/recozai/reporting/{domain,application,adapter,api}/*` and `web/ReportingController.java`.
  - `frontend/src/app/pages/denial-detail.ts`, `dashboard.ts` (clearly badged AI Recovery Assistant panel, confidence & source-locator pills, accept/edit/reject controls, and dashboard KPI breakdown: Submitted vs. Denied vs. Estimated Recoverable vs. Appealed vs. Confirmed Recovered vs. Written-Off XOF).
- **Acceptance Criteria**:
  1. External AI calls are blocked (`422 AI_EXTERNAL_PROCESSING_DISABLED`) unless both `app.ai.external-enabled=true` and `clinic_settings.ai_external_approved=true`.
  2. Prompt injection payloads embedded in PDF evidence or payer rejection text cannot bypass schema validation, cite foreign-clinic UUIDs, or mutate claim/financial state.
  3. Missing case facts in AI-generated appeals produce explicit `[PLACEHOLDER]` markers that must be resolved before `BILLING_MANAGER` approval.
- **Rollback / Checkpoint Strategy**: AI module can be instantaneously disabled at runtime via `app.ai.external-enabled=false` or per-clinic settings without affecting manual recovery workflows.

---

### Phase 5 — Security/Privacy Review, Backup/Restore Rehearsal (`B13`) & Pilot Readiness (Prompts 07, 08, 09, 10)
- **Objective**: Complete the Senegal Law No. 2008-12 / CDP engineering review checklist, automated backup/restore reconciliation script, synthetic bilingual AI evaluation suite, and pilot operations documentation.
- **Dependencies**: Phases 0–4.
- **Affected Files / Modules**:
  - `docs/security-privacy-checklist-senegal.md`, `docs/testing-strategy.md`, `docs/deployment-guide.md`, `docs/pilot-runbook.md`, `docs/backup-and-restore.md`, `docs/incident-response.md`, `docs/payer-configuration-guide.md`, `docs/ai-configuration-guide.md`, `docs/operations-checklist.md`.
  - `scripts/rehearse-backup-restore.sh` and extended Playwright/Testcontainers suites.
- **Acceptance Criteria**:
  1. All negative cross-tenant, role-escalation, prompt-injection, and quarantine tests pass.
  2. Backup/restore rehearsal script demonstrates `RESTORE_GATE=CLOSED` blocking traffic, `SECURITY_EPOCH` invalidating old sessions/grants, reconciliation passing, and `RESTORE_GATE=OPEN` restoring service.

---

## 3. Risk Register & Mitigation Matrix

| Risk | Severity | Mitigation in Plan |
|---|---|---|
| **Accidental overwrite of financial or claim history** | High | Enforced at PostgreSQL level via `forbid_mutation()` triggers on `claim_revision`, `claim_line`, `claim_submission`, `financial_journal_entry`, `appeal_submission_record`, and `audit_event`. |
| **Hallucinated payer deadlines or rules from AI** | High | `recovery_case.due_date` can only be set by an `APPROVED_SOURCED` `payer_rule_version` (backed by a `CLEAN` payer PDF) or explicit `STAFF_CONFIRMED` entry; AI suggestions cannot set `due_date` or `APPROVED` rule state. |
| **Cross-border health data leakage to external LLM** | High | Claims store only `pseudonymous_patient_ref` (no patient names/national IDs/clinical notes), outbound AI context is sanitized, and external AI is hard-gated off until clinic/legal approval is configured. |
| **Unverified appeal marked as submitted** | Medium | `DenialCaseStatus.SUBMITTED_TO_PAYER` requires both a `BILLING_MANAGER`-approved `appeal_draft_version` and an append-only `appeal_submission_record` with channel + reference/document evidence. |
| **Heterogeneous Senegal clinic billing exports** | Medium | `B06I` uses a staging preview (`claim_import_batch` / `claim_import_row`) with configurable column mappings and row-level error reporting rather than rigid fixed-column assumptions. |
