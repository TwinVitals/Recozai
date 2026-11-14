# Recozai — Current & Target Architecture Blueprint

> **Scope**: Repository-specific architecture for evolving **Recozai** into the production-grade Senegal AI Denial Recovery Agent while preserving the existing Java 25 / Spring Boot 4.1.2 modular monolith, PostgreSQL 18.6 schema, and Angular 22.2.5 SPA.

---

## 1. Current Architecture (As-Implemented Baseline)

### 1.1 Runtime Topology & Stack
Recozai is deployed as a **single-database modular monolith** behind an unprivileged Nginx reverse proxy (`deploy/compose.dev.yml`):

- **Edge / Frontend Container (`frontend/`)**:
  - `nginxinc/nginx-unprivileged:1.30.6-alpine` serving the static Angular `22.2.5` (TypeScript `6.0.2`, RxJS `7.8.2`, `@ngx-translate/core` `18.1.0`) bundle on port `8080` and proxying `/api/` to `http://backend:8080`.
  - Enforces security headers (`Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Permissions-Policy`).
- **Application Container (`backend/`)**:
  - `eclipse-temurin:25-jre` running Spring Boot `4.1.2` (`sn.recozai:recozai:0.1.0-SNAPSHOT`), Spring WebMVC, Spring Security, Bean Validation, Spring JDBC (`NamedParameterJdbcTemplate` wrapped in `sn.recozai.platform.Database`), Spring Data JPA (Hibernate `7.4.13.Final`), Flyway, and Jackson 3 (`tools.jackson.databind.ObjectMapper`).
  - Also supports a non-web CLI execution profile (`--spring.profiles.active=operator --spring.main.web-application-type=none`) for Ed25519 custodian-signed bootstrap and account recovery grants (`FoundationConfiguration.operatorRunner`).
- **Persistence Container (`postgres`)**:
  - `postgres:18.6-alpine` storing all relational, session, rate-limit, audit, claim, payer, and document metadata tables managed by Flyway (`V1__foundation.sql` through `V6__documents_and_sourced_rules.sql`).
- **Private Quarantine Document Store**:
  - Local filesystem storage mounted at `app.document-storage-root` (`/var/lib/recozai/documents/{clinicId}/{documentId}.bin`) with `0700` directory permissions (`LocalQuarantineStorageAdapter`).

### 1.2 Current Hexagonal Package Structure & ArchUnit Invariants
`backend/src/test/java/sn/recozai/ArchitectureTest.java` enforces strict dependency rules across `sn.recozai`:
1. **`..domain..` packages**: Pure Java domain models and state machines (`Identity`, `PayerDomain`, `ClaimDomain`, `DocumentDomain`). Forbidden from importing `org.springframework..`, `jakarta..`, or `java.sql..`.
2. **`..application..` packages**: Use-case orchestration and port interfaces (`IdentityService`, `CustodianService`, `ClinicService`, `AuditService`, `PayerService`, `ClaimService`, `DocumentService`). Forbidden from importing `..adapter..` or `..web..`.
3. **`..adapter..` packages**: Infrastructure implementations (`IdentityJdbcAdapter`, `RateLimits`, `ClinicJdbcAdapter`, `AuditJdbcAdapter`, `PayerJdbcAdapter`, `ClaimJdbcAdapter`, `DocumentJdbcAdapter`, `LocalQuarantineStorageAdapter`, `ConfigurableDocumentScanner`, `PdfTextExtractorAdapter`).
4. **`..web..` packages**: REST controllers (`ApiController`, `PayerController`, `ClaimController`, `DocumentController`) and RFC 9457 error translation (`Problems`). Forbidden from importing `..adapter..` or `..platform..`.
5. **Cross-cutting packages**:
   - `sn.recozai.access`: `Actor`, `CurrentActor`, `Role` (`CLINIC_ADMIN`, `BILLING_MANAGER`, `BILLING_STAFF`, `CLAIMS_REVIEWER`, `MANAGEMENT_VIEWER`).
   - `sn.recozai.shared`: `Failure`, `Money`, `Etags`, `PageSlice`, `Pagination`, `Transactions`.
   - `sn.recozai.platform`: `Database` (`NamedParameterJdbcTemplate` helper), `SpringTransactions`, `SystemReferenceEntity`.

### 1.3 Current Implemented Capabilities (`B01`–`B06`, `B08`)
| Module | Current Package / Frontend Route | Implemented Scope |
|---|---|---|
| **Identity & Access (`B01`–`B03`)** | `sn.recozai.identity.*`, `sn.recozai.access.*` / `/login`, `/select-clinic`, `/settings` | Opaque `RECOZAI_SESSION` cookie (`SHA-256` hashed in `app_session`), CSRF cookie/header (`RECOZAI-XSRF-TOKEN` / `X-RECOZAI-XSRF-TOKEN`), DB-backed rate limits (`auth_rate_limit`), Ed25519 custodian grants (`operator_authorization`), invitations, clinic membership switching, and `RESTORE_GATE` / `SECURITY_EPOCH` session fencing. |
| **Clinic & Audit (`B04`)** | `sn.recozai.clinic.*`, `sn.recozai.audit.*` / `/settings`, `/dashboard` | Clinic configuration (`name`, `locale`, `timezone`, `currency`, `fraction_digits`) with `ETag`/`If-Match` optimistic locking; append-only `audit_event` table protected by PostgreSQL `forbid_mutation()` trigger. |
| **Payers, Categories & Rules (`B05`)** | `sn.recozai.payer.*` / `/payers` | Clinic-scoped payers (`IPM`, `MUTUELLE`, `CMU`, `PRIVATE_INSURER`, etc.), bilingual denial categories (`label_fr`, `label_en`), and versioned payer rules (`TERMINOLOGY_MAPPING`, `REQUIRED_DOCUMENTS`, `DEADLINE_RULE`, `APPEAL_WORKFLOW`) with `DRAFT -> APPROVED -> SUPERSEDED` lifecycle. |
| **Manual Claims & Submissions (`B06`)** | `sn.recozai.claim.*` / `/claims` | Draft claims (`claim_record`, `claim_draft_line`), registration into immutable `claim_revision` (`R1..Rn`) and `claim_line` rows with frozen currency/scale and SHA-256 `content_hash`, plus idempotent append-only `claim_submission` events (`SUBMISSION`, `RESUBMISSION`, `METADATA_CORRECTION`). |
| **Documents & Sourced Rules (`B08`)** | `sn.recozai.document.*` / `/payers`, `/claims` | XOR parent (`payer_id` or `claim_id`) uploads, magic-byte validation (`PDF`, `PNG`, `JPEG`), fail-closed malware scan (`QUARANTINED` vs `CLEAN`), native PDF text extraction into immutable `evidence_version` (`EXTRACTED`), human reviewer correction (`HUMAN_VERIFIED`), and document-backed payer rule approval. |

### 1.4 Known Baseline Defects to Remediate First
1. **Compile Error in `ClaimService.java:15`**: Missing functional interface identifier `ClinicPolicyReader` (`public interface    {` vs `private final ClinicPolicyReader clinicPolicies;` at line 39).
2. **Multipart Upload Size Mismatch**: `DocumentDomain.MAX_DOCUMENT_BYTES` permits `20 MiB`, but `frontend/nginx.conf:5` sets `client_max_body_size 16k;` and `backend/src/main/resources/application.yml` omits `spring.servlet.multipart.max-file-size` (defaulting to `1MB`).
3. **Claim Document Write RBAC Mismatch**: `DocumentService.requireParentWriteAccess` calls `current.requireClaimRead()` (allowing `CLAIMS_REVIEWER`) instead of `current.requireClaimWrite()` (`BILLING_MANAGER`, `BILLING_STAFF`) for mutations on claim documents.
4. **Frontend Localization Gaps**: Missing translation keys (`evidenceDocumentLabel`, `noDocumentSelected`), hardcoded `"Superseded: "` in `claims.ts:366`, and unlocalized enum option labels in `payers.ts:253-279`.

---

## 2. Target Architecture (Senegal AI Denial Recovery Agent)

### 2.1 Architectural Principles
- **Preserve the Modular Monolith**: Do not split into microservices or add external message brokers/caches. Keep all bounded contexts in `sn.recozai.*` under the existing `domain` / `application` / `adapter` / `api` + `web` structure enforced by `ArchitectureTest`.
- **Single Source of Truth in PostgreSQL**: Extend the existing composite-foreign-key `(clinic_id, id)` schema via forward-safe Flyway migrations (`V7+`) and database-level immutability triggers (`forbid_mutation()`).
- **Exact Financial Integrity (`XOF`)**: All monetary amounts use `sn.recozai.shared.Money` (`BigDecimal` + explicit ISO-4217 currency + frozen scale; `XOF` default with `scale = 0`). Financial history is recorded in an append-only ledger (`financial_journal_entry`), never via destructive overwrites.
- **Configured & Sourced Payer Rules**: Senegal payers (IPMs, mutuelles, CMU schemes, private insurers) have distinct, evolving rules. Deadlines and required documents are never hardcoded; a rule can drive automated due dates only when `state = 'APPROVED'` and `deadline_basis = 'APPROVED_SOURCED'` (or when explicitly entered and confirmed by staff on a case with `STAFF_CONFIRMED` provenance).
- **Assistive, Privacy-Gated AI**: AI output is an assistive draft (`ai_suggestion_record`), never the system of record. External AI calls are disabled by default (`app.ai.external-enabled=false` and per-clinic privacy approval gate) and require human review/approval before any appeal is marked `APPROVED_FOR_SUBMISSION` or any financial/case state changes.

### 2.2 Mapping Current Modules to Target Bounded Contexts & Extension Points

| Bounded Context | Target Backend Package | Evolution Strategy (Reuse vs. New) | Frontend Route / Module |
|---|---|---|---|
| **1. Organizations / Clinics** | `sn.recozai.clinic` | **Extend existing** `ClinicSettings` & `clinic_settings` with AI external-processing governance flags (`ai_external_approved`, `ai_approval_reference`, `ai_approved_by`, `ai_approved_at`) while preserving `ETag` optimistic locking. | `/settings` |
| **2. Users, Roles & Permissions** | `sn.recozai.identity`, `sn.recozai.access` | **Reuse existing** `IdentityService`, `CustodianService`, `Actor`, `CurrentActor`, and `Role`. Add fine-grained role helper checks on `Actor` for recovery cases, appeals, financial journal entries, imports, exports, and AI assistance. | `/login`, `/select-clinic`, `/settings` |
| **3. Patients / Beneficiaries (Minimized)** | Embedded value object within `sn.recozai.claim` | **Reuse existing** privacy-by-design `pseudonymous_patient_ref` (`VARCHAR(120)`) plus optional non-identifying payer beneficiary/member card code (`beneficiary_card_ref`) and coverage scheme type. Never store patient full name, national ID, phone, address, or clinical history. | Masked/minimized badges in `/claims` & `/denials` |
| **4. Payers, Contracts & Rules** | `sn.recozai.payer` | **Reuse existing** `PayerDomain`, `PayerService`, `PayerJdbcAdapter`. Add lookup port `PayerRuleLookup` so `denial`, `recovery`, and `ai` modules can query active `APPROVED` rules (`TERMINOLOGY_MAPPING`, `REQUIRED_DOCUMENTS`, `DEADLINE_RULE`, `APPEAL_WORKFLOW`) effective on a given date. | `/payers` |
| **5. Claims & Batch Imports (`B06`, `B06I`)** | `sn.recozai.claim` | **Extend existing** `ClaimDomain` / `ClaimService` with configurable CSV/XLSX batch import (`claim_import_batch`, `claim_import_row`): staging preview, column mapping, row-level validation, duplicate detection, idempotent commit into `claim_record`/`claim_revision`, and rejected-row report. | `/claims` (manual + import wizard) |
| **6. Immutable Financial Journal (`B07`)** | `sn.recozai.ledger` *(new package)* | **New bounded context** managing append-only `financial_journal_entry` rows per claim/revision/case (`SUBMITTED`, `DENIED`, `DISCREPANCY_ADJUSTMENT`, `RECOVERED_PAYMENT`, `WRITE_OFF`, `REVERSAL`) and computing deterministic claim/case financial balances (`submitted`, `denied`, `recovered`, `writtenOff`, `openDisputed`). | Embedded financial summary cards in `/claims`, `/denials`, `/dashboard` |
| **7. Denial & Discrepancy Intake (`B09`)** | `sn.recozai.denial` *(new package)* | **New bounded context** recording immutable/versioned `denial_event` rows linked to `claim_record`, `claim_revision`, optional `claim_line`, `payer`, optional `denial_category`, and optional `document_object` (`remittance`/`rejection notice`), preserving verbatim `original_reason_text` and `source_reason_code`. | `/denials` (work queue & intake modal) |
| **8. Recovery Cases, Tasks & Appeals (`B10`)** | `sn.recozai.recovery` *(new package)* | **New bounded context** managing `recovery_case`, `recovery_task` (missing-document checklist, deadline reminders), `appeal_draft_version` (`fr-SN`/`en`, human approval gate `BILLING_MANAGER`), `appeal_submission_record` (append-only external submission evidence), and `payer_response_record`. | `/denials/:id` (case workspace, checklist, appeal editor) |
| **9. Documents & Staged Extraction (`B08`)** | `sn.recozai.document` | **Extend existing** `DocumentDomain` / `DocumentService` to allow binding documents to `recovery_case` (in addition to `payer` or `claim`) and storing structured field-level staged extractions (`document_field_extraction` with field key, value, confidence, page/region locator, and verification state). | `/documents` & split-pane viewer in `/denials/:id`, `/claims`, `/payers` |
| **10. AI Orchestration (`B11`)** | `sn.recozai.ai` *(new package)* | **New bounded context** with vendor-neutral port `AiModelPort`, deterministic test/demo adapter (`DeterministicMockAiAdapter`), configurable external adapter, privacy gate check, prompt registry (`prompt_version`), strict JSON Schema validation, usage/latency telemetry (`ai_invocation_log`), and staged suggestions (`ai_suggestion_record`). | AI Recovery Assistant drawer/panel in `/denials/:id` |
| **11. Analytics, Reporting & Exports (`B12`)** | `sn.recozai.reporting` *(new package)* | **New bounded context** providing read-only tenant-scoped aggregations (submitted vs. denied vs. estimated recoverable vs. appealed vs. actually recovered vs. written-off XOF, denial reasons by payer, deadline aging) and audited CSV/printable exports. | `/dashboard`, `/reports` |
| **12. Deadlines & In-App Notifications** | `sn.recozai.notification` *(new package)* | **New bounded context** computing deterministic deadline alerts and task reminders from `recovery_case` / `recovery_task` without requiring external SMS/email providers in the pilot. | Header alert indicator & `/tasks` queue |
| **13. Audit Trail (`B04`)** | `sn.recozai.audit` | **Reuse existing** `Audit` port and `audit_event` table; add cataloged action codes for imports, financial entries, denial events, case transitions, AI runs, appeal approvals/submissions, and exports. | `/settings` (audit log viewer) |

---

## 3. Trust Boundaries & Tenant Isolation Across All Surfaces

```text
[ Browser SPA (Angular 22) ]
       │  HTTPS / Same-Origin (:8080)
       │  Cookie: RECOZAI_SESSION (HttpOnly, SameSite=Strict)
       │  Header: X-RECOZAI-XSRF-TOKEN + If-Match + Idempotency-Key
       ▼
[ Nginx Reverse Proxy ]  (Security headers, rate/body size enforcement)
       │
       ▼
[ Spring Security Filter Chain + FoundationFilter ]
  1. RestoreGate check (RESTORE_GATE == OPEN, else 503)
  2. CSRF verification on mutating verbs
  3. Persistent RateLimits check on auth/public endpoints (429)
  4. Session token SHA-256 lookup + idle/max expiry + security_epoch validation
  5. Resolves immutable Actor(userId, clinicId, membershipId, login, locale, roles)
       │
       ├──► [ Application Services (Hexagonal Use Cases) ]
       │      • Every method derives clinicId exclusively from CurrentActor.get().clinicId()
       │      • Explicit role check (Actor.require...) before any read/write
       │      • Single Spring @Transactional boundary via Transactions.execute(...)
       │      • Synchronous Audit.record(...) inside the same DB transaction
       │
       ├──► [ PostgreSQL 18.6 ]
       │      • Every tenant table has clinic_id UUID NOT NULL
       │      • Composite UNIQUE (clinic_id, id) + Composite FKs (clinic_id, <ref>_id)
       │      • Immutability triggers (forbid_mutation) on audit, revisions, submissions, ledger, evidence
       │
       ├──► [ Local Quarantine File Storage (/var/lib/recozai/documents/{clinicId}/{docId}.bin) ]
       │      • Path normalization & prefix check (!target.startsWith(root))
       │      • Quarantine gate: QUARANTINED files cannot be downloaded, extracted, or cited
       │
       └──► [ External AI Provider Boundary (DISABLED BY DEFAULT) ]
              • Requires app.ai.external-enabled=true AND clinic_settings.ai_external_approved=true
              • Outbound payload sanitizer strips any accidental identifiers; sends only pseudonymous
                refs, payer rule excerpts, and untrusted document snippets wrapped in XML data delimiters
```

### Surface-by-Surface Isolation Rules
1. **API Layer**: Client payloads **never** include `clinicId`. Any lookup by resource UUID executes `WHERE clinic_id = :clinicId AND id = :id`; cross-tenant hits return `404 RESOURCE_NOT_FOUND` (indistinguishable from non-existent IDs to prevent enumeration).
2. **Database Layer**: Every new table (`claim_import_batch`, `claim_import_row`, `financial_journal_entry`, `denial_event`, `recovery_case`, `recovery_task`, `appeal_draft_version`, `appeal_submission_record`, `payer_response_record`, `document_field_extraction`, `ai_invocation_log`, `ai_suggestion_record`) enforces composite `UNIQUE (clinic_id, id)` and composite foreign keys `FOREIGN KEY (clinic_id, parent_id) REFERENCES parent(clinic_id, id)`.
3. **Async / Background Operations**: The pilot runs synchronous request-scoped extraction and AI calls with bounded timeouts. Any scheduled deadline evaluation or retry job iterates per-clinic inside an explicit tenant context and never shares state across clinics.
4. **Search, Analytics & Exports**: All list filters, dashboard aggregations, and CSV exports bind `:clinicId` as the leading index column (`(clinic_id, ...)`). Every export records an `AUDIT_EVENT` (`REPORT_EXPORTED`).
5. **Files & Quarantine**: Private storage is never exposed via Nginx static mounts. Downloads stream through `GET /api/v1/documents/{id}/content` after checking `Actor` role, `clinic_id`, and `quarantine_status = 'CLEAN'`.
6. **AI Retrieval (RAG / Context Assembly)**: Context assembly queries only `APPROVED` payer rules, the target claim/revision, and `CLEAN` document evidence belonging to `actor.clinicId()`. Cross-tenant retrieval is impossible at both the SQL predicate level and domain validation level.

---

## 4. API Patterns, Validation, Error Model, Idempotency & Concurrency

All new endpoints under `/api/v1/...` strictly follow the established repository conventions:

1. **Error Contract (`sn.recozai.web.Problems` + `sn.recozai.shared.Failure`)**:
   - All errors return `application/problem+json` (RFC 9457 `ProblemDetail`) with:
     - `title`: HTTP reason phrase
     - `status`: HTTP status code (`400`, `401`, `403`, `404`, `409`, `412`, `422`, `428`, `429`, `503`)
     - `detail`: Safe, non-sensitive summary
     - `code`: Machine-readable uppercase constant (e.g., `STALE_VERSION`, `SOURCE_DOCUMENT_REQUIRED`, `AI_EXTERNAL_PROCESSING_DISABLED`, `APPEAL_APPROVAL_REQUIRED`, `LEDGER_BALANCE_EXCEEDED`)
     - `correlationId`: Server-generated UUID logged alongside server diagnostics
2. **Optimistic Concurrency (`sn.recozai.shared.Etags`)**:
   - Mutable aggregate roots (`clinic_settings`, `clinic_membership`, `payer`, `denial_category`, `claim_record`, `document_object`, `recovery_case`, `recovery_task`, `appeal_draft_version`) maintain `version BIGINT NOT NULL DEFAULT 0`.
   - `GET` / `POST` / `PATCH` responses emit `ETag: "<version>"`.
   - Mutating `PATCH` and state-transition `POST` endpoints require `If-Match: "<version>"` (`428 PRECONDITION_REQUIRED` if missing, `412 STALE_VERSION` if mismatched).
3. **Idempotency (`Idempotency-Key` Header)**:
   - Append-only creation of external events or batch commits (`POST /api/v1/claims/{id}/submission-records`, `POST /api/v1/claim-imports`, `POST /api/v1/claims/{id}/denial-events`, `POST /api/v1/recovery-cases/{id}/appeal-submissions`, `POST /api/v1/recovery-cases/{id}/financial-events`) require `Idempotency-Key` (`1..120` ASCII chars).
   - Stored with `(clinic_id, parent_id, idempotency_key)` uniqueness and `request_hash CHAR(64)` (SHA-256 of canonical request payload). Replaying the same key + hash returns the original result; replaying the same key with a different payload returns `409 IDEMPOTENCY_KEY_CONFLICT`.
4. **Transaction Boundaries (`sn.recozai.shared.Transactions`)**:
   - Every command executes inside a single `tx.execute(...)` block combining domain validation, state mutation, financial journal append (where applicable), and `audit.record(...)`. If `audit.record(...)` fails, the entire transaction rolls back (verified by `FoundationIntegrationTest.auditIsImmutableAndFailureRollsBackMutation`).
5. **Pagination (`sn.recozai.shared.Pagination` & `PageSlice<T>`)**:
   - List endpoints return `PageSlice<T>(items, page, size, totalElements, totalPages)` with `page >= 0` and `1 <= size <= 100`.

---

## 5. Source-Backed Payer-Rule Versioning & Provenance

Senegal payers (IPMs, mutuelles, CMU schemes, private insurers) do not follow a single statutory claims standard. Recozai enforces **provenance-first rule governance** (`PayerDomain`, `V5`, `V6`):

- **Rule Kinds**:
  - `TERMINOLOGY_MAPPING`: Maps payer-specific rejection codes/phrases (`sourceCode`, `sourceTerm`) to clinic-configured bilingual `denial_category.category_key`.
  - `REQUIRED_DOCUMENTS`: Lists required attachment keys (`requiredDocumentKeys`, e.g., `bordereau_signe`, `ordonnance`, `bulletin_prise_en_charge`, `facture_normalisee`) for submission or appeal.
  - `DEADLINE_RULE`: Defines `trigger` (`CLAIM_SUBMISSION`, `DENIAL_RECEIPT`, `DISCREPANCY_NOTICE`, `PAYER_INFORMATION_REQUEST`), `offsetValue`, `unit` (`DAYS`, `BUSINESS_DAYS`), `dayBasis` (`CALENDAR`, `BUSINESS`), `timezone` (`Africa/Dakar`), and `calendarSource`.
  - `APPEAL_WORKFLOW`: Ordered administrative steps (`stepOrder`, `code`, `labelFr`, `labelEn`, `requiresHumanVerification`).
- **Lifecycle & Verification States**:
  - `DRAFT`: Editable configuration created by `BILLING_MANAGER`; `canDriveDueDate = false`.
  - `APPROVED`: Transitioned via `POST /api/v1/payer-rule-versions/{id}/approval` by `BILLING_MANAGER`. Requires a `sourceDocumentId` referencing a `CLEAN` payer document in the same clinic (`enforce_payer_rule_version_mutation` DB trigger). Automatically transitions any prior `APPROVED` version of the same `(clinic_id, payer_id, rule_kind)` to `SUPERSEDED`.
  - `SUPERSEDED`: Historical immutable record retained so older claims/appeals retain exact provenance of the rule version that governed them.
- **Deadline Provenance Extension for Recovery Cases (`B10`)**:
  - Every `recovery_case.due_date` must record `due_date_basis`:
    1. `APPROVED_SOURCED`: Computed automatically from an `APPROVED` `DEADLINE_RULE` version (`due_date_rule_version_id NOT NULL`) and a verified trigger date.
    2. `STAFF_CONFIRMED`: Explicitly entered by `BILLING_MANAGER` / `BILLING_STAFF` with a mandatory `due_date_source_note` (and optional `due_date_evidence_document_id`).
    3. `UNKNOWN`: `due_date IS NULL` when neither an approved rule nor staff-confirmed deadline exists. The system never invents or guesses a deadline.

---

## 6. AI Provider Abstraction, Trust Controls & Vendor Data Flow

### 6.1 Hexagonal AI Port & Adapters (`sn.recozai.ai`)
- **`AiModelPort` (Application Port)**:
  - Vendor-neutral interface accepting structured task requests (`EXTRACTION_STRUCTURING`, `DENIAL_CLASSIFICATION`, `RECOVERY_RECOMMENDATION`, `APPEAL_DRAFT_GENERATION`, `CASE_QA_ASSISTANT`) with explicit `promptVersion`, `locale` (`fr-SN` | `en`), schema identifier, and sanitized context payload.
  - No model name (e.g., Gemini, OpenAI, Anthropic) is hardcoded in domain, application, or frontend code; provider and model IDs are injected solely via environment configuration (`app.ai.provider`, `app.ai.model-id`, `app.ai.timeout-ms`, `app.ai.max-retries`).
- **Adapters**:
  1. `DisabledAiAdapter` (Default in production/staging when `app.ai.external-enabled=false`): Fails closed with `422 AI_EXTERNAL_PROCESSING_DISABLED`, keeping all manual workflows 100% functional.
  2. `DeterministicMockAiAdapter` (Used only in automated tests and explicitly labeled `development`/`test` demo mode): Produces deterministic, schema-valid structured outputs tagged `provider = "SYNTHETIC_DEMO_MOCK"` so the UI visibly badges outputs as synthetic demo assistance.
  3. `ConfigurableHttpAiAdapter` (External provider adapter): Enabled only when both environment flag `app.ai.external-enabled=true` and tenant flag `clinic_settings.ai_external_approved=true` (recorded with legal/CDP reference by `CLINIC_ADMIN`) are satisfied.

### 6.2 Prompt Injection Defense & Untrusted Input Isolation
- OCR text, PDF extracted text (`evidence_version.extracted_text`), and payer rejection strings (`denial_event.original_reason_text`) are treated as **untrusted data**.
- Prompts separate immutable system instructions from untrusted document/payer excerpts using strict structural delimiters and explicit instruction-hierarchy rules: content inside data blocks cannot alter output schemas, invoke actions, override payer rules, or fabricate facts.
- **Output Validation**: Every AI response is parsed through Jackson 3 against a strict task DTO and validated by `AiDomain`:
  - Cited `payerRuleVersionId` or `evidenceVersionId` must belong to the active `clinicId` and target `claimId`/`payerId` (hallucinated UUIDs are rejected).
  - Monetary figures in AI output are never written to `claim_record` or `financial_journal_entry`.
  - Appeal drafts must preserve placeholders (e.g., `[À COMPLÉTER PAR LE PRATICIEN : ...]`) when supporting evidence is missing.

### 6.3 Mandatory Human Review & Approval Gate
- AI outputs are persisted as `ai_suggestion_record` (`status = 'PENDING_REVIEW' | 'ACCEPTED' | 'EDITED_AND_ACCEPTED' | 'REJECTED'`).
- An appeal draft (`appeal_draft_version`) cannot transition to `APPROVED_FOR_SUBMISSION` without an explicit `POST /api/v1/recovery-cases/{id}/appeals/{versionId}/approval` call by a human user holding `BILLING_MANAGER` (recording `approved_by`, `approved_at`, and `AUDIT_EVENT = APPEAL_VERSION_APPROVED`).
- AI has **zero** capability to invoke external submission, alter claim amounts, or write off balances.

---

## 7. Bilingual (`fr-SN` and `en`) Localization Architecture

1. **Locale Precedence**:
   - `Authenticated user preference (app_user.locale)` → `Clinic default (clinic_settings.locale)` → `'fr-SN'` fallback.
   - Switching language via `PATCH /api/v1/me/preferences` updates `app_user.locale` immediately without invalidating the session (`FoundationIntegrationTest.localeSwitchPreservesSessionAndLogoutRevokesIt`).
2. **Frontend Presentation Boundary**:
   - `@ngx-translate/core` (`frontend/src/app/core/catalog.ts`) holds complete, parity-checked `fr-SN` and `en` dictionaries.
   - All backend enum codes (`ClaimState`, `RuleKind`, `DeadlineTrigger`, `DenialCaseStatus`, `AppealStatus`, `PaymentStatus`, `RecoveryOutcome`, `JournalEntryKind`, `Problem.code`) remain language-neutral uppercase identifiers on the wire and are translated at the UI boundary via dedicated translation keys (e.g., `enum.deadlineTrigger.DENIAL_RECEIPT`).
   - Dates and numbers use `Intl.DateTimeFormat(locale, { timeZone: clinicTimezone })` and `Intl.NumberFormat(locale, { style: 'currency', currency: 'XOF', maximumFractionDigits: 0 })`.
3. **Source Text vs. Translated Assistance**:
   - Payer names (`payer.originalName`), verbatim rejection wording (`denial_event.originalReasonText`), and extracted document text (`evidence_version.extractedText`) are displayed verbatim as authoritative source text, never silently overwritten by machine translation.
   - Generated appeal drafts (`appeal_draft_version`) store an explicit `locale` (`fr-SN` or `en`) selected by the billing user so staff can generate and edit French appeals (standard in Senegal) or English appeals (for international/regional private insurers).

---

## 8. Observability, Audit Events, Security Controls, Backup/Restore & Release Strategy

- **Transactional Audit Catalog (`audit_event`)**:
  - Every material state change, configuration update, document download/export, AI invocation, appeal approval, external submission record, and financial journal entry writes an immutable row in `audit_event` within the same transaction.
  - No PII/PHI or raw document text is written to `audit_event` or application logs (`Problems.java` logs only `code` + `correlationId`).
- **Health & Readiness**:
  - `/actuator/health` and `/actuator/health/readiness` (with `show-details: never` publicly) verify PostgreSQL connectivity.
- **Backup, Restore & Reconciliation (`B13`)**:
  - Restoring a database backup requires starting the application with `RESTORE_GATE=CLOSED` (which returns `503 RESTORE_RECONCILIATION_REQUIRED` to all user traffic) and rotating `SECURITY_EPOCH` (which invalidates all pre-restore sessions and custodian grants).
  - Only after running the reconciliation check (verifying Flyway schema version, audit table integrity, and document storage checksums against `document_object.sha256_hex`) does the operator set `RESTORE_GATE=OPEN`.

---

## 9. External Dependencies & Launch Blockers

| Blocker Category | Specific External Requirement | Fallback in Pilot Architecture |
|---|---|---|
| **Senegal Legal & CDP (`Law No. 2008-12`)** | Qualified Senegalese legal review, CDP declaration/authorization for health-related administrative data, processor contracts, and cross-border transfer review. | System runs on local/in-country infrastructure with `pseudonymous_patient_ref` minimization and `app.ai.external-enabled=false` until formal approval is recorded. |
| **Payer Conventions & Tariffs** | Signed IPM / mutuelle / CMU / private insurer conventions and official rejection code lists per clinic. | `payer_rule_version` starts in `DRAFT`; staff upload the clinic's PDF convention (`B08`) and `BILLING_MANAGER` approves rules with source provenance (`APPROVED_SOURCED`). |
| **Electronic Payer APIs** | Most Senegal IPMs and mutuelles operate via physical *bordereaux*, email, or portal uploads rather than public APIs. | Manual channel tracking (`BORDEREAU_PAPIER`, `PORTAIL_IPM`, `COURRIER`, `EMAIL`) with mandatory human submission reference and uploaded receipt evidence (`document_object`). |
| **Clinic Billing Exports** | Clinic billing/invoicing software export layouts. | Configurable CSV/XLSX import column mapping profile per clinic (`B06I`) plus manual claim entry (`B06`). |
| **Malware Scanner & OCR** | Production ClamAV (`clamd`) daemon and OCR engine for scanned paper images. | Fail-closed `DocumentScannerPort` (quarantines when scanner is offline) and staged `EvidenceExtractorPort` with human reviewer correction (`HUMAN_VERIFIED`). |
