# ADR-0003: Privacy-Gated, Provider-Agnostic AI Recovery Agent with Mandatory Human Approval

- **Status**: Accepted
- **Context**:
  Recozai uses AI to assist billing teams with document field extraction, denial classification, missing-evidence checklists, bilingual (`fr-SN` / `en`) appeal drafting, and case Q&A. Under Senegal privacy expectations (Law No. 2008-12 and CDP review inputs) and healthcare safety rules, external AI transmission must not occur without explicit governance approval, untrusted OCR/payer text must not hijack instructions, and AI must never autonomously submit appeals or modify verified claim/financial records.
- **Decision**:
  1. Introduce `AiModelPort` in `sn.recozai.ai.application` with three adapters:
     - `DisabledAiAdapter` (default when `app.ai.external-enabled=false`).
     - `DeterministicMockAiAdapter` (used only in automated tests and explicitly badged local synthetic demos).
     - `ConfigurableHttpAiAdapter` (requires both `app.ai.external-enabled=true` and `clinic_settings.ai_external_approved=true`).
  2. Never hardcode a specific vendor or model name in source code; inject `provider`, `model-id`, `timeout-ms`, and `max-retries` via configuration.
  3. Wrap all untrusted OCR/PDF evidence and payer rejection text in structural data delimiters and validate all AI outputs against strict Jackson 3 schemas and tenant UUID ownership checks (`cited_rule_version_ids`, `cited_evidence_version_ids`).
  4. Persist AI outputs only in `ai_suggestion_record` (`PENDING_REVIEW`) and `document_field_extraction` (`UNVERIFIED`). Require `BILLING_MANAGER` human approval (`POST /api/v1/recovery-cases/{id}/appeals/{versionId}/approval`) before any appeal draft can be marked `APPROVED` or externally submitted.
- **Consequences**:
  - The platform works end-to-end when external AI is disabled.
  - Prompt injection inside uploaded PDFs cannot cross tenant boundaries, fabricate verified rules, or trigger unauthorized state changes.
