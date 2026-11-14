# ADR-0002: Source-Backed Payer Rule Governance and Deadline Provenance

- **Status**: Accepted
- **Context**:
  In Senegal, IPMs, mutuelles de santé, CMU schemes, and private insurers each maintain their own conventions, tariffs, rejection terminologies, required supporting documents, and appeal deadlines. Hardcoding universal deadlines or letting an LLM invent a filing deadline creates operational and financial risk for clinics.
- **Decision**:
  1. Extend the `V4`/`V6` `payer_rule_version` governance model (`DRAFT -> APPROVED -> SUPERSEDED`) as the sole automated rule engine across `denial`, `recovery`, and `ai` modules.
  2. A `DEADLINE_RULE` version can compute automated `recovery_case.due_date` values (`due_date_basis = 'APPROVED_SOURCED'`) if and only if `state = 'APPROVED'`, `source_document_id` references a `CLEAN` payer document in the same clinic, and `deadline_basis = 'APPROVED_SOURCED'`.
  3. When no `APPROVED_SOURCED` deadline rule exists for a payer, `recovery_case.due_date` defaults to `NULL` (`due_date_basis = 'UNKNOWN'`) unless a staff member with `BILLING_STAFF` or `BILLING_MANAGER` explicitly enters a confirmed date (`due_date_basis = 'STAFF_CONFIRMED'`) with a mandatory `due_date_source_note`.
- **Consequences**:
  - Zero fabricated deadlines or silent assumptions across heterogeneous Senegal payers.
  - Historical cases retain exact references (`due_date_rule_version_id`) to the rule version that governed them even after a rule is `SUPERSEDED`.
