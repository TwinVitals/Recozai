# AI Denial Recovery Agent — Complete Copilot Prompt Library

> Use these prompts one at a time in order. Start with Prompt 00. The first prompt is read-only. Do not execute all implementation prompts in one request.

## Contents

- [00-repo-discovery.prompt.md](.github/prompts/00-repo-discovery.prompt.md)
- [01-architecture-and-gap-analysis.prompt.md](.github/prompts/01-architecture-and-gap-analysis.prompt.md)
- [02-healthcare-ux-redesign.prompt.md](.github/prompts/02-healthcare-ux-redesign.prompt.md)
- [03-foundation-and-domain.prompt.md](.github/prompts/03-foundation-and-domain.prompt.md)
- [04-claims-and-denial-workflow.prompt.md](.github/prompts/04-claims-and-denial-workflow.prompt.md)
- [05-ai-recovery-agent.prompt.md](.github/prompts/05-ai-recovery-agent.prompt.md)
- [06-bilingual-localization.prompt.md](.github/prompts/06-bilingual-localization.prompt.md)
- [07-security-privacy-senegal.prompt.md](.github/prompts/07-security-privacy-senegal.prompt.md)
- [08-testing-and-quality.prompt.md](.github/prompts/08-testing-and-quality.prompt.md)
- [09-production-readiness.prompt.md](.github/prompts/09-production-readiness.prompt.md)
- [10-final-repository-review.prompt.md](.github/prompts/10-final-repository-review.prompt.md)

---



---

# 00 — Read-only repository discovery

Act as a principal software architect and senior engineer. Investigate this existing IntelliJ-opened repository before anyone changes it. The product context is in `docs/product-context.md`; repository-wide constraints are in `.github/copilot-instructions.md`.

## Critical mode
**READ ONLY. Do not create, edit, move, rename or delete files. Do not run destructive commands, migrations, formatters that rewrite files, dependency upgrades or database operations.** You may run safe read-only inspection and existing non-mutating checks where practical. Do not ask me to restate project details that are discoverable from the repository.

## Inspect systematically
1. Repository structure and module boundaries; identify monorepo/multi-module setup.
2. Frontend: framework/version, language, routing, state, component library, styling, localization, API clients, forms, tests and build scripts.
3. Backend: language/runtime/framework versions, modules/layers, API style, validation, security, persistence, migrations, asynchronous jobs, integrations, tests and build scripts.
4. Data: database(s), entities/schema, tenancy model, patient/beneficiary data, claim/denial/appeal-related tables, indexes, migrations and sample/demo data.
5. Authentication/authorization: identity/session flow, roles/permissions, backend enforcement, tenant scoping and security configuration.
6. AI/document capability: existing model clients, prompts, OCR, file storage, retrieval, vector search, output schemas, observability, human approvals and vendor data flow.
7. UX: current navigation, design system, responsive behavior, accessibility, bilingual readiness, error/loading/empty states and key screens.
8. Operations: Docker, CI/CD, environment configuration, secrets, logging, monitoring, health checks, backup/restore, deployment and infrastructure.
9. Engineering health: TODO/FIXME, duplicate logic, dead code, dependency risks, test coverage gaps, build warnings and known failure points.
10. Git state: inspect status and changed files without reverting or modifying anything. Do not expose secrets discovered during inspection; report only that a secret-like value was found and where it should be rotated.

## Output required
Create a structured report **in the chat only** with:

- Executive summary of what the project currently is.
- Exact detected stack and versions, with file paths proving each finding.
- Repository/module map and main runtime entry points.
- What already works (do not propose rebuilding these parts).
- Existing business capabilities relevant to denial recovery.
- Missing capabilities mapped to the product brief.
- Existing architecture strengths and risks.
- Security/privacy/tenant-isolation findings, with severity and evidence.
- Frontend UX and bilingual gaps.
- Build/test commands discovered and whether you actually ran them; report exact outcomes.
- External prerequisites: payer specs, contracts, sandbox credentials, hosting/legal approvals, AI/OCR vendors, EHR APIs, etc.
- Risks and unknowns clearly separated from verified facts.
- A recommended phased implementation plan that reuses the current stack.

## Rules for findings
- Do not infer technology from folder names alone; confirm via manifests/config.
- Do not assume the app has no feature merely because a search did not find an exact label; inspect relevant code paths.
- Never print secrets, tokens, patient identifiers or production data.
- Do not make legal compliance claims. Identify review topics only.
- Do not invent Senegal payer rules, API availability, tariffs or deadlines.
- Prioritize a small, complete pilot vertical slice over a broad rewrite.

End with a clear statement: **“Discovery completed — no repository files were modified.”** If you cannot verify something, label it unverified rather than guessing.



---

# 01 — Architecture and implementation blueprint

Use the read-only repository discovery findings and `docs/product-context.md`. First verify that you are working from the current repository state; do not assume a previous report is still accurate if code has changed.

## Goal
Produce a repository-specific, incremental architecture and delivery plan for evolving this existing application into the Senegal AI Denial Recovery Agent. Reuse the detected stack, conventions, working modules and deployment approach. Do not force a technology replacement.

## Required analysis
1. Map current modules to target capabilities and identify extension points.
2. Recommend bounded contexts/modules for organizations/clinics, users/permissions, patients/beneficiaries, payers/contracts/rules, claims, denial cases, recovery tasks, appeals, documents, AI orchestration, reporting, notifications, audit and integrations.
3. Define domain entities, relationships, lifecycle/status models and invariants. Distinguish claim status, denial case status, appeal status, payment status and recovery outcome.
4. Define trust boundaries and tenant isolation across API, database, async jobs, cache, search, files, exports, analytics and AI retrieval.
5. Define API patterns, validation, error model, idempotency, concurrency and transaction boundaries consistent with existing project conventions.
6. Define source-backed payer-rule versioning, effective dates, verification state and provenance.
7. Define AI provider abstraction, structured output validation, prompt versioning, safe failure behavior, human approval and vendor data controls.
8. Define i18n architecture for complete `fr-SN` and `en` support, including persisted preferences and localized generated communications.
9. Define observability, audit events, security controls, backup/restore and release strategy.
10. Identify dependencies and external decisions that block a genuine production launch.

## Architecture rules
- Do not introduce microservices unless the current system already has justified service boundaries or a clear measured need.
- Prefer the smallest architectural changes that meet maintainability, security and scale requirements.
- If Angular is present, follow its detected version and established patterns; use RxJS Observable-based data services and strict TypeScript where applicable.
- If Java/Spring Boot is present, align with existing module boundaries and use domain/application/ports/adapters separation where beneficial.
- XOF must use safe exact numeric handling. Never use floating-point money.
- Do not assume US healthcare standards or a universal Senegal claims format.
- Keep payer-specific requirements configurable, sourced, versioned and reviewable.

## Artifacts to create
Create or update only these planning documents unless an existing equivalent is found; merge rather than duplicate:
- `docs/architecture.md` — current architecture and target architecture, clearly separated.
- `docs/domain-model.md` — entities, relationships, state machines and invariants.
- `docs/implementation-plan.md` — phases, dependencies, risk, acceptance criteria and rollback/checkpoint strategy.
- `docs/decisions/` with concise ADRs for major decisions that cannot be inferred from the existing code.

Do not change application source code in this phase. Do not install dependencies or run migrations.

## Output
Summarize key decisions, exact documents changed, unresolved assumptions, major risks, and the first small implementation slice. Keep the plan measurable and specific to this repository.



---

# 02 — Modern healthcare UX/UI redesign

Act as a lead product designer and senior frontend engineer specializing in secure healthcare and financial SaaS. Inspect the existing frontend and design system before changing anything.
Use the attached UI image in Reference as the primary visual reference. Redesign my existing application's UI to achieve this level of modern healthcare SaaS quality. Preserve existing business functionality, API integrations and architecture. Implement the design using the existing frontend technology and reusable components. Ensure complete French and English localization and responsive layouts. Do not replace working modules with mockups.

## Product experience
Create a modern, calm, premium healthcare operations interface for a Senegal-focused AI Denial Recovery Agent. The product helps billing/revenue staff manage rejected claims, understand causes, organize supporting documents, draft appeals and track actual recoveries. It is not an EHR and must not look like a generic admin template.

## Reference
AI Denial Recovery Dashboard Senegal.png

## Design principles
- Trustworthy, professional, calm and legible; avoid visual noise, gratuitous gradients and excessive animation.
- Strong hierarchy, consistent spacing, readable typography, restrained semantic color, accessible contrast and keyboard support.
- Desktop-first workflows for claims/billing teams, with responsive behavior for tablets and mobile.
- Clear distinction between verified data, AI suggestions, estimates, missing evidence and human-approved actions.
- Never use color alone to communicate status.
- Every interactive control must perform a real action or be explicitly disabled with an explanation.
- Every screen needs loading, empty, error, success and permission-denied states where relevant.
- Keep patient information masked/minimized in dense lists and notifications.

## Required information architecture
Adapt to the existing application; do not create duplicate navigation if similar screens exist.
- Overview dashboard.
- Claims and imports.
- Denial/rejection work queue.
- Denial case detail with original rejection, normalized reason, evidence, confidence/provenance, recovery checklist and event history.
- AI recovery assistant panel, always clearly labeled as AI-generated support.
- Appeal editor with French/English choice, source evidence and human approval state.
- Payers, contracts and configurable payer rules.
- Patient/beneficiary minimal profile.
- Document center.
- Tasks, deadlines and notifications.
- Analytics and reports.
- Organization/clinic, users, roles, preferences, language and security/audit settings.

## Key workflow UX
Design the shortest clear workflow: import/create claim → record rejection → analyze documents → verify extracted facts → review payer rule sources → choose next action → draft appeal → authorized human approval → record actual submission evidence → record payer response and amount actually recovered.

## Localization requirements
French (`fr-SN`) and English (`en`) are first-class from the start. French may be the initial default for Senegal, but the default is configurable. No visible hardcoded strings. Translate labels, helper text, validation, dialogs, toasts, charts, tables, emails, reports, dates, numbers, and document templates. Do not translate payer's original rejection text; show it as source content with a normalized translation/summary alongside it.

## Implementation method
1. Inventory the current UI routes, shared components, styles and design tokens.
2. Propose a screen-by-screen redesign and component reuse map before coding.
3. Implement one cohesive vertical slice at a time, starting with the main navigation, dashboard and denial-case workflow.
4. Use typed models and existing API contracts; do not fabricate backend fields or fake production data.
5. Add realistic synthetic demo data only through the existing development/demo mechanism and label it clearly.
6. Add/update component and accessibility tests.
7. Run frontend lint/build/tests and report exact results.

Do not change backend domain logic in this phase. Do not add a new UI library without justifying the bundle, accessibility and maintenance cost. Do not break existing screens or routes; preserve compatibility or document a migration plan.

## Acceptance criteria
- UX is consistent with the current project's technology and conventions.
- Primary denial-recovery workflow is discoverable and usable.
- AI-generated suggestions are visibly distinct from verified facts.
- French and English flows are complete for implemented screens.
- Responsive and keyboard-accessible behavior is tested.
- No inert buttons, dummy data in production pathways or fake status updates.



---

# 03 — Secure application foundation and domain implementation

Implement the next agreed foundation slice from `docs/implementation-plan.md`. Reinspect the affected code and current Git state before editing. If the plan conflicts with current code, explain the discrepancy and update the plan before proceeding.

## Scope
Implement only the foundation/domain capabilities that are missing and required for the first clinic pilot:
- organization/clinic context;
- user, role and permission integration with the existing identity model;
- tenant-aware authorization and persistence boundaries;
- core domain model for payer, payer contract/rule, claim, denial case, recovery task, appeal, document metadata and audit event where absent;
- centralized validation and error handling;
- migration strategy and audit-safe state changes.

Do not rebuild working authentication or user management. Extend it safely.

## Domain invariants
- Tenant/organization identity is derived from the authenticated principal and authorized membership, never trusted from an arbitrary client-supplied tenant ID.
- Every query and background job is tenant-scoped. Add negative tests proving one tenant cannot read or mutate another tenant's records.
- Keep original payer rejection text/reference and source document immutable or versioned.
- Preserve separate status machines for claim, denial case, appeal, payment and recovery outcome.
- Financial changes retain who/when/why/source and previous value. Do not silently overwrite financial history.
- Store payer rules with payer association, effective dates, version, source, source locator, verification status and reviewer.
- Treat payer policies as configuration, not hardcoded universal assumptions.
- Use exact monetary types and currency codes. For XOF, choose a representation consistent with the existing database and language; document precision and rounding behavior. No floating-point arithmetic.

## Engineering requirements
- Follow detected stack, framework versions, conventions and existing module structure.
- Use explicit DTO/API contracts; do not expose persistence entities directly unless the current architecture intentionally does so and it is safe.
- Validate at the API and domain boundaries.
- Use transactions around related claim/denial/recovery updates.
- Use idempotency or deduplication for import/event operations where duplicate requests are possible.
- Add indexes/constraints based on real query patterns and tenant scope.
- Migrations must be forward-safe and include a documented rollback/recovery strategy. Never run destructive production migrations.
- Keep logging structured and free of patient/health details and secrets.

## Workflow
1. Identify the exact missing domain elements and existing equivalents.
2. Describe the minimal file/module scope and migration impact.
3. Implement in small increments.
4. Add tests for domain invariants, validation, tenant isolation, concurrency and financial precision.
5. Run targeted tests and build.
6. Review the diff for accidental rewrites and compatibility issues.

Do not add AI calls, payer integrations or UI scope here unless explicitly necessary for the agreed foundation slice. Report exact files, migrations, tests and outstanding risks.



---

# 04 — Claims, denial cases and recovery workflow

Implement a complete, working claims and denial-management vertical slice using the existing backend/frontend architecture. Read `docs/product-context.md`, `.github/copilot-instructions.md`, the domain model and implementation plan. Inspect the current claim-related code before editing; extend rather than duplicate it.

## Required capabilities
### Claim intake
- Create/edit draft claims with validation.
- Manual entry and configurable CSV/XLSX import if not already available.
- Import preview, column mapping, row-level validation, duplicate detection, rejected-row report and safe retry.
- Store payer, provider/clinic, beneficiary reference, claim reference, dates, service/line summaries, amounts, currency, submission reference and source provenance appropriate to the existing scope.
- Preserve source file and import batch metadata under secure access controls.
- Do not expand into a full medical record or store unnecessary clinical detail.

### Claim and denial lifecycle
- Maintain original payer status/rejection wording alongside normalized application categories.
- Support configurable denial categories such as eligibility/membership, authorization, missing documentation, tariff/contract discrepancy, duplicate, non-covered service, deadline, invalid beneficiary/claim data and payer-specific reasons. Categories must be configurable; do not imply they apply identically to every payer.
- Create denial cases linked to a claim and payer.
- Record rejection date, original reason, normalized category, denied amount, potential recoverable estimate (clearly marked as estimate), assigned owner, priority, source documents, next action and activity history.
- Keep separate statuses for claim, denial case, appeal, payment and recovery outcome.
- Provide filters, pagination, search, sorting and bulk actions only where authorization and audit behavior are correct.

### Recovery case workflow
- New → analysis/verification → action required → appeal draft → awaiting approval → approved → submitted (only with evidence) → payer review → partially recovered/recovered/closed/escalated.
- Make transitions explicit and validated. Do not allow arbitrary status editing to bypass required review.
- Create tasks/reminders for missing documents and approaching deadlines. A deadline is valid only if sourced from verified contract/rule or explicitly entered and confirmed by staff.
- Store submission channel, timestamp, submitting user, evidence/reference and payer response. Never mark as submitted merely because a draft exists.
- Record partial payment, adjustment, reversal and write-off with reason and audit trail.

## User experience
- Build a searchable claims work queue and a denial work queue.
- Denial detail must show original rejection, normalized reason, linked claim/payer, supporting evidence, source/provenance, timeline, task checklist and recovery summary.
- Label AI estimates/suggestions distinctly from verified financial values.
- Support complete French/English labels and validation.
- Include loading/error/empty/permission states.

## Security and data quality
- Enforce authorization on server side for every API and operation.
- Test tenant isolation for list, detail, import, export, documents, update and background processing.
- Validate file imports, rows, dates, currency, amounts and references.
- Ensure idempotent import behavior and clear duplicate resolution.
- Avoid logging patient identifiers or document contents.

## Completion evidence
Run backend/frontend builds and targeted tests. Add unit, integration and end-to-end coverage for: manual claim creation, import preview/commit, denial creation, invalid transition rejection, evidence attachment, human approval gate, submission evidence, partial recovery and tenant isolation. Report real test results and known gaps. Do not use fake APIs or fabricate payer-specific rules.



---

# 05 — AI-powered denial recovery agent

Act as an AI architect, healthcare RCM specialist and security engineer. Inspect the current application, approved architecture and any existing AI/OCR integration before implementation. Implement a secure, provider-independent AI assistive workflow; do not replace working integrations without a reason.

## AI use cases in scope
1. Document extraction from denial letters, payer statements, claims attachments and supporting documents.
2. Structured identification of claim/payer references, dates, rejection text, amounts, missing documents and relevant source sections.
3. Denial classification using verified payer-specific configuration and claim context.
4. Evidence-linked administrative recovery recommendations.
5. French/English appeal and reconsideration draft generation.
6. Staff-facing chat/assistant for questions about authorized claim and denial data.

Out of scope: diagnosis, treatment recommendations, autonomous clinical necessity determinations, autonomous appeal submission, automatic write-offs or unreviewed changes to verified financial/clinical data.

## Architecture
- Reuse/introduce an AI provider port and adapters; keep business logic vendor-independent.
- Separate extraction, classification, recommendation, retrieval and appeal-drafting use cases.
- Support configurable model/provider per task through configuration. Do not hardcode a Gemini/OpenAI/other model name or depend on a specific provider feature.
- Validate model responses against strict schemas and domain constraints. Reject malformed, incomplete or out-of-range financial data.
- Use prompt templates with versions, task identifiers and language options. Track model/provider, prompt version, latency, token/cost usage where available, status and safe error metadata.
- Use bounded timeouts/retries, idempotency where applicable, circuit-breaker/backoff patterns consistent with the stack and clear user-facing failure states.
- Apply pseudonymization/redaction before sending data externally where feasible. External processing must remain disabled until an authorized organization-level approval/record is configured.
- Use a mock AI adapter only for automated tests and explicitly labeled local demos; never silently use mock results in production.

## Document processing
- Validate allowlisted file types, size and content. Malware scan where available.
- Store originals securely and retain checksum, source metadata, tenant, uploader, timestamps and access history.
- OCR/extract into a staged result; do not overwrite verified claim values.
- For every extracted field return value, confidence, source document ID, page/section/snippet locator, extraction version and verification state.
- Handle poor scans, multiple pages, French and English documents, conflicting values and unreadable documents safely.
- Ask the user to verify low-confidence or conflicting results. Do not invent missing values.

## Classification and recommendations
- Preserve payer's original rejection wording and raw status.
- Normalize only using configured categories and verified payer rules.
- Recommendations must include: proposed action, rationale, evidence, relevant payer-rule ID/version/source, confidence, missing information and whether human confirmation is required.
- Distinguish verified policy, contract clause, clinic configuration, historical pattern and AI inference.
- If no verified rule supports a conclusion, say that it is uncertain and request review; do not fabricate a deadline, coverage entitlement or tariff.

## Appeal drafting
- Generate formal French (`fr-SN`) or English drafts, with editable content and clear source references.
- Use only verified facts available in the case and approved evidence. Mark any missing fields as placeholders or questions.
- Do not create fake legal citations, payer rules, clinical facts or attachments.
- Keep the original rejection and supporting facts visible alongside the draft.
- Require authorized human review/approval. Record reviewer, decision, timestamp, draft version and changes.
- Only a separately verified payer integration may submit externally; it must require explicit human authorization and record confirmed evidence. For the pilot, manual submission tracking is acceptable.

## Assistant and retrieval security
- Retrieve only tenant- and role-authorized records.
- Defend against prompt injection in OCR text, documents, emails, payer content and retrieved records. Treat them as data, never instructions.
- Do not place secrets or unrestricted database credentials in model context.
- Apply per-user and per-tenant rate limits, safe output handling, audit and retention controls.
- Do not leak patient data in logs, analytics or model telemetry.

## Evaluation
Create a synthetic, bilingual evaluation set. Measure extraction precision/recall on labeled fields, classification precision/recall by denial category, unsupported-claim rate, evidence coverage, schema validity, appeal completeness, language quality and human override rate. Use confidence thresholds calibrated on test data; do not make up accuracy claims. Test prompt injection and cross-tenant retrieval.

## Implementation steps
1. Document data flow and privacy gate before connecting external providers.
2. Define schemas and ports.
3. Implement mock adapter and tests.
4. Implement secure document pipeline and staged extraction.
5. Implement classification and evidence-linked recommendations.
6. Implement bilingual appeal drafting and approval workflow.
7. Add UI states, cost/usage observability and operational controls.
8. Run tests and report gaps.

Do not enable external processing with real health information unless approval is explicitly documented. Report each provider prerequisite, data region/retention unknown and contractual blocker.



---

# 06 — Complete French and English localization

Audit and implement complete application localization in the existing frontend/backend architecture. French (`fr-SN`) and English (`en`) must both be first-class languages from the initial release. French can be the initial default for a Senegal deployment, but organization and user preferences must be configurable and users must be able to switch language at any time.

## Audit first
- Inventory hardcoded UI strings, labels, tooltips, placeholders, validation, errors, toasts, dialogs, menus, navigation, tables, filters, statuses, charts, emails, notifications, reports, PDFs and generated templates.
- Identify locale state, persistence, translation libraries/files and any backend-generated text.
- Do not change payer's original rejection wording; preserve it as source content and provide a separately labeled translation/summary when requested.

## Implementation requirements
- Use the project's existing i18n system; do not add another library unless needed and justified.
- Centralize translation keys; avoid concatenating fragments that produce grammatically incorrect messages.
- Provide complete `fr-SN` and `en` dictionaries for all implemented features. No missing-key fallback should silently display raw translation keys in production.
- Persist user preference and apply organization default for new users; define deterministic precedence (explicit user preference → organization preference → `fr-SN` fallback).
- Localize dates, times, number formats, XOF display, pluralization, validation and accessibility labels.
- Keep API/domain enum values language-neutral. Translate them at the UI/presentation boundary.
- Translate email, notification and appeal templates in the selected recipient/user language.
- Support UTF-8 accents, apostrophes and typography correctly.
- Do not translate legal, contractual or payer source excerpts as though the translation were authoritative; label generated translations as assistance and retain source text.
- Avoid layout overflow with longer French labels and text scaling.

## Quality checks
- Add tests for language switching, preference persistence, default precedence and no missing keys.
- Test all main user journeys in both languages: sign-in, claims, denial detail, AI analysis, appeal edit/review, payer configuration and reports.
- Verify date/currency formatting and French accented characters in exports/templates.
- Report untranslated strings by screen and close all P1/P2 gaps for the implemented release scope.

Do not change business logic or claim statuses solely to support localization. Keep the change incremental and compatible with current routes/API contracts.



---

# 07 — Security, privacy and Senegal readiness review

Act as an application security architect and privacy engineering lead. Perform a code-backed review of the existing implementation, then fix confirmed issues in small, testable increments. Do not claim legal certification or compliance. For matters requiring legal interpretation, create an explicit local-review item.

## Scope
1. Authentication, session/token handling, password policy, MFA options and account lifecycle.
2. Backend authorization for every route/action; tenant membership validation and object-level access control.
3. Cross-tenant leakage through database queries, background jobs, caches, search, AI retrieval, documents, exports, logs and analytics.
4. Sensitive health data minimization, masking, encryption in transit/at rest, retention, secure deletion and backup controls.
5. File uploads, content-type verification, size limits, malware scanning, path traversal, private storage and download authorization.
6. API input validation, output encoding, injection risks, CORS, CSRF where applicable, rate limiting and abuse protection.
7. Secrets, environment config, dependency vulnerability exposure, container/image security and CI/CD permissions.
8. AI/OCR vendor data processing, prompt injection, model context boundaries, telemetry and cross-border data flows.
9. Audit coverage for access, view/download/export, changes, AI processing, appeal review/approval/submission and financial adjustment.
10. Error handling/logging/monitoring to ensure patient details, document content, credentials and tokens are not exposed.

## Senegal privacy review checklist
Create or update `docs/security-privacy-checklist-senegal.md` with topics for qualified local counsel/privacy review, including:
- Senegal Law No. 2008-12 and applicable implementing instruments;
- CDP formalities, declarations/authorizations and any health-data-specific requirements;
- legal basis, purpose, notice and consent requirements where applicable;
- controller/processor roles and contracts with hosting, OCR, LLM, messaging and support vendors;
- confidentiality and access controls;
- data-subject rights and processes;
- retention, archiving and deletion;
- incident/breach response and notification duties;
- hosting location and international transfer safeguards/authorizations;
- patient data used in development, analytics and AI training (default: do not use);
- documentation and evidence required before live patient data is enabled.

Treat this as an engineering checklist, not legal advice. Do not infer that consent alone solves every requirement or that offshore hosting is automatically allowed. Require qualified Senegalese legal/privacy review before production processing of real health data.

## Remediation method
- Start with a findings table: severity, affected path/API, evidence, exploit scenario, impact, recommended fix and test.
- Fix confirmed critical/high issues first, preserving application behavior.
- Add negative tests for unauthorized roles, cross-tenant access and insecure file access.
- Add automated checks for secrets and dependency vulnerabilities where repository tooling supports them.
- Do not run destructive security scans against production systems.
- Do not disclose secret values in report or logs; identify location and advise rotation.
- Do not mark an item fixed without a test or other verifiable evidence.

## Release gate
List all open critical/high findings, legal/privacy approvals, external vendor approvals, retention/hosting decisions and incident-response gaps. The system is not production-ready for real patient data while any blocking item remains unresolved. Demo mode must use synthetic records and be clearly identified.



---

# 08 — Automated testing, reliability and quality gates

Inspect the current testing stack and improve coverage for the implemented denial-recovery product without replacing working test infrastructure. Use synthetic and appropriately anonymized data only.

## Test portfolio
### Frontend
- Unit/component tests for forms, tables, filters, denial detail, appeal editor, AI assistant states and localization.
- Service/API contract tests, error/loading/retry behavior and cancellation/lifecycle behavior.
- Routing and permission UX tests (but never treat them as a substitute for backend authorization tests).
- Accessibility checks for keyboard operation, focus, labels, contrast and status communication.

### Backend
- Domain unit tests for claim/denial/appeal/payment/recovery state machines and invariants.
- API integration tests for validation, authentication, authorization and error contracts.
- Repository tests for tenant scoping, constraints, indexes and migration behavior.
- Financial calculation tests for XOF precision, rounding, partial recovery, reversals, write-offs and currency separation.
- Import idempotency, duplicate detection and malformed-row tests.
- File security and access-control tests.

### AI/document pipeline
- Schema validation and malformed-output handling.
- Synthetic French and English documents, poor scans, multi-page extraction and conflicting values.
- Extraction confidence and human-verification gating.
- Denial classification against a labeled dataset; report precision/recall and confusion matrix if sample size supports it.
- Evidence/source traceability and unsupported-assertion tests.
- Prompt injection in OCR and payer documents.
- Cross-tenant and role-restricted retrieval tests.
- AI outage, timeout, retry, quota and provider-switch tests.
- Appeal draft tests proving missing facts become placeholders and human approval is required.

### End-to-end journeys
1. Create/import claim → record payer rejection → upload evidence → extract → verify → classify → create recovery task.
2. Review verified payer rule → generate French appeal → edit → approve → record manual submission evidence → record partial payer payment.
3. Repeat in English.
4. A user from another tenant attempts to access claim, document, export and AI result and is denied.
5. An unapproved organization attempts external AI processing and is blocked safely.

## Quality requirements
- Use deterministic test fixtures and isolated test data.
- Do not call real payer endpoints, production email/SMS, or paid LLM/OCR services in normal CI.
- Provide mocks/fakes at provider ports, clearly separated from production adapters.
- Keep tests independent and repeatable.
- Do not weaken a test or delete coverage just to obtain a passing build.
- Do not claim a percentage coverage target unless it is measured. Prioritize critical business/security paths.

## Execution
1. Inventory existing test tools and commands.
2. Identify critical untested flows and add tests.
3. Run targeted suites, then all relevant suites and builds.
4. Report exact commands, pass/fail/skip counts, duration if available and any flaky tests.
5. Fix root causes for failures; do not hide them.
6. Update `docs/testing-strategy.md` with how to run tests locally and in CI.

Do not declare production readiness if critical authorization, tenant-isolation, financial-accuracy, migration or human-approval tests are missing or failing.



---

# 09 — Production operations and clinic pilot readiness

Prepare the existing application for a controlled Senegal clinic pilot. Inspect actual deployment/runtime configuration first. Do not deploy to production, modify cloud resources, send real messages, connect live payer accounts, run destructive migrations or use real patient data as part of this prompt.

## Runtime and delivery
- Verify reproducible frontend/backend builds and version pinning.
- Review Docker/container images and compose/manifests if present.
- Ensure environment-specific configuration for local, test, staging and production.
- Move secrets to the configured secret manager/environment injection; never commit credentials.
- Add health/readiness checks and graceful shutdown where supported.
- Ensure migrations are versioned, reviewed, forward-safe and backed by a restore plan.
- Configure structured, redacted logs, metrics, error reporting and correlation IDs.
- Define backup frequency, encryption, retention, restore tests and recovery objectives with the operator.
- Add dependency/image scanning and CI checks where feasible.
- Add rollback instructions for application and database changes.

## Pilot operations
- One clinic can be onboarded without manual database edits.
- Organization and clinic data are isolated.
- Users and roles can be provisioned and disabled.
- Payer configuration can be entered with source, effective dates and verification state.
- Manual claim entry and CSV/XLSX import work when payer APIs do not exist.
- The whole denial-to-recovery workflow works in synthetic demo mode.
- Staff can review AI extraction and appeal drafts and approve or reject them.
- Manual submission evidence can be recorded without pretending to have a payer integration.
- Dashboard distinguishes denied, estimated recoverable, submitted for appeal, paid/recovered and written-off values.
- User support, incident handling, audit review and data correction procedures are documented.

## Required documents
Create/update, reusing existing equivalent documents:
- `docs/deployment-guide.md`
- `docs/pilot-runbook.md`
- `docs/backup-and-restore.md`
- `docs/incident-response.md`
- `docs/payer-configuration-guide.md`
- `docs/ai-configuration-guide.md`
- `docs/operations-checklist.md`

Document actual commands, prerequisites, environment variables by name (never values), roles, health checks, rollback and support escalation. Document unavailable external services honestly.

## Production gates
Explicitly classify readiness for:
1. Local development with synthetic data.
2. Staging/demo with synthetic data.
3. Controlled pilot using real health data.
4. General commercial production.

For each, list pass/fail/blocked and evidence. Real-data pilot is blocked until privacy/legal approvals, CDP requirements, hosting/cross-border review, contracts with processors, security findings and operating procedures are resolved. External AI processing remains disabled unless specifically approved and configured. Do not state “production-ready” if blockers remain.

## Final validation
Run the builds, tests, migration validation and non-destructive deployment checks available in the repository. Record exact command and actual result. Do not create cloud infrastructure or deploy without explicit separate authorization. Finish with a go/no-go decision and a prioritized blocker list.



---

# 10 — Final end-to-end repository review and release decision

Act as an independent principal engineer, security reviewer and healthcare product quality lead. Review the actual current repository, diffs and test evidence. Do not rely solely on earlier Copilot summaries. The goal is to determine what is genuinely complete, what is incomplete and whether a controlled pilot/release is justified.

## Review areas
1. Product workflow completeness: claim intake, denial case, evidence, AI extraction/classification, recommendations, appeal draft, human approval, submission evidence, payer response and actual recovered amount.
2. Bilingual completeness: French `fr-SN` and English `en` across screens, validation, notifications, templates and reports.
3. Modern UX: consistent navigation/design system, responsive layouts, accessibility, loading/error/empty states and no inert controls.
4. Architecture: reuse of existing project, clear module boundaries, maintainability, database migrations and external adapter isolation.
5. Security: backend authorization, least privilege, tenant isolation, secure files, secrets, logging, rate limits and audit trail.
6. Privacy: health data minimization, external AI/OCR data flows, hosting and international transfers, retention, processor contracts and Senegal local review gates.
7. AI trust: structured outputs, provenance, confidence, no fabricated facts, prompt-injection resistance, cost/usage tracking and mandatory human approval.
8. Financial integrity: XOF precision, status distinctions, actual cash/credit confirmation, partial recovery, reversals and auditable adjustments.
9. Reliability: build/test results, import idempotency, async job retries, monitoring, backup/restore, rollback and failure states.
10. Pilot operations: onboarding, payer configuration, synthetic data, manual fallback, support, runbooks and documentation.

## Required checks
- Inspect `git status` and current diff; do not discard changes.
- Run discovered frontend/backend builds, lint, unit/integration/e2e tests and migration validation where practical.
- Check for placeholder implementations, fake production responses, hardcoded payer rules, unsupported API claims, hardcoded user-facing strings, unsafe financial math and tenant-unscoped queries.
- Verify that every external integration is labeled real, sandbox, mock or manual fallback.
- Verify that claims and appeals are never silently marked submitted and AI cannot submit without explicit approved human action.
- Verify no tests or reports disclose secrets or real patient data.

## Output format
Provide:
- Executive release verdict: **No-go**, **Demo-ready with synthetic data**, **Controlled pilot candidate pending approvals**, or **Pilot-ready**. Do not use “production-ready” as a generic compliment.
- Feature matrix: complete / partial / missing / blocked with evidence.
- Security/privacy findings sorted by severity.
- Build/test matrix with exact command and actual result.
- Known defects and operational risks.
- External/legal/contractual prerequisites.
- Recommended next 10 actions ordered by risk and value.
- Files and docs changed during this review (if any).

Do not change application source during this review unless I explicitly request remediation. Do not claim legal compliance, certification, payer integration or test success without evidence. Finish with a truthful go/no-go decision.

