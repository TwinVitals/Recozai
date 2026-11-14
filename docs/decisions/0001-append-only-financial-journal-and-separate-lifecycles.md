# ADR-0001: Append-Only Financial Journal and Five Orthogonal Lifecycle State Machines

- **Status**: Accepted
- **Context**:
  Healthcare revenue-recovery workflows in Senegal involve initial claim submissions (`XOF` integer scale), partial payments, contractual tariff adjustments, full or partial rejections, multi-step reconsideration/appeals, partial recoveries, and administrative write-offs. Collapsing these into a single mutable status or mutable balance columns on `claim_record` causes loss of financial auditability and conflates estimated recoverable amounts with confirmed cash/credit.
- **Decision**:
  1. Model five distinct, orthogonal lifecycles:
     - **Claim Status (`ClaimState`)**: `DRAFT`, `REGISTERED`, `SUBMITTED`, `ARCHIVED`, `VOID`.
     - **Denial Case Status (`DenialCaseStatus`)**: `NEW`, `ANALYSIS_VERIFICATION`, `ACTION_REQUIRED`, `APPEAL_DRAFT`, `AWAITING_APPROVAL`, `APPROVED_FOR_SUBMISSION`, `SUBMITTED_TO_PAYER`, `PAYER_REVIEW`, `ESCALATED`, `CLOSED`.
     - **Appeal Draft Status (`AppealStatus`)**: `DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `SUPERSEDED`, `SUBMITTED`.
     - **Claim Payment Status (`ClaimPaymentStatus`)**: Derived deterministically from the financial ledger (`UNPAID_PENDING`, `DENIED_UNPAID`, `PARTIALLY_PAID_DISPUTED`, `RECOVERED_PARTIAL`, `PAID_OR_RECOVERED_FULL`, `WRITTEN_OFF`).
     - **Recovery Outcome (`RecoveryOutcome`)**: `PENDING`, `RECOVERED_FULL`, `RECOVERED_PARTIAL`, `UPHELD_WRITTEN_OFF`, `WITHDRAWN_CORRECTED_CLAIM`.
  2. Persist all verified monetary movements in an append-only `financial_journal_entry` table protected by the PostgreSQL `forbid_mutation()` trigger. Corrections must use explicit `REVERSAL` entries referencing the reversed entry (`UNIQUE (clinic_id, reverses_entry_id)`).
  3. Keep `estimated_recoverable_amount` on `recovery_case` strictly separated from `financial_journal_entry`, tagged with `estimate_basis`.
- **Consequences**:
  - Every `XOF` figure on dashboards and claim details is reconstructible from immutable ledger rows.
  - AI or staff estimates can never pollute confirmed recovery totals.
