package sn.recozai.recovery.api;

import java.time.*;
import java.util.*;

public final class RecoveryModels {
    private RecoveryModels() {}

    public enum LedgerEntryType {
        INITIAL_PAYMENT,
        CONTRACTUAL_ADJUSTMENT,
        DENIAL_DISPUTED,
        RECOVERY_PAYMENT,
        WRITE_OFF,
        REVERSAL
    }

    public enum ClaimPaymentStatus {
        UNPAID_PENDING,
        DENIED_UNPAID,
        PARTIALLY_PAID_DISPUTED,
        RECOVERED_PARTIAL,
        PAID_OR_RECOVERED_FULL,
        WRITTEN_OFF
    }

    public enum DenialKind { FULL, PARTIAL }

    public enum CategoryMappingSource {
        APPROVED_PAYER_RULE,
        MANUAL_STAFF_SELECTION
    }

    public enum DenialCaseStatus {
        NEW,
        ANALYSIS_VERIFICATION,
        ACTION_REQUIRED,
        APPEAL_DRAFT,
        AWAITING_APPROVAL,
        APPROVED_FOR_SUBMISSION,
        SUBMITTED_TO_PAYER,
        PAYER_REVIEW,
        ESCALATED,
        CLOSED
    }

    public enum RecoveryOutcome {
        PENDING,
        RECOVERED_FULL,
        RECOVERED_PARTIAL,
        UPHELD_WRITTEN_OFF,
        WITHDRAWN_CORRECTED_CLAIM
    }

    public enum DueDateBasis {
        APPROVED_SOURCED,
        STAFF_CONFIRMED,
        UNKNOWN
    }

    public enum EstimateBasis {
        UNESTIMATED,
        SOURCED_RULE,
        STAFF_CONFIRMED
    }

    public enum RecoveryTaskKind {
        VERIFY_SOURCE_WORDING,
        COLLECT_SUPPORTING_DOCUMENTS,
        RECONCILE_EXPECTED_AMOUNT,
        REQUEST_CLINICAL_CLARIFICATION,
        PREPARE_APPEAL_DRAFT,
        MANAGER_ESCALATION_REVIEW,
        CUSTOM_FOLLOW_UP
    }

    public enum RecoveryTaskStatus {
        OPEN,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }

    public enum AppealStatus {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        SUPERSEDED,
        SUBMITTED
    }

    public enum OccurrencePrecision { DATE, INSTANT }

    public record Money(String amount, String currency) {}

    public record LedgerEntryView(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID revisionId,
        LedgerEntryType entryType,
        Money amount,
        Money previousOutstandingAmount,
        Money resultingOutstandingAmount,
        LocalDate occurredOn,
        String payerReference,
        UUID sourceDocumentId,
        UUID reversesEntryId,
        boolean reversed,
        String reason,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    public record ClaimLedgerSummary(
        UUID claimId,
        UUID clinicId,
        UUID revisionId,
        int revisionNumber,
        String currency,
        int currencyScale,
        Money submittedAmount,
        Money initialPaidAmount,
        Money contractualAdjustmentAmount,
        Money deniedDisputedAmount,
        Money recoveredAmount,
        Money writtenOffAmount,
        Money outstandingAmount,
        Money openDisputedAmount,
        ClaimPaymentStatus paymentStatus,
        List<LedgerEntryView> entries
    ) {
        public ClaimLedgerSummary {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record DenialEventView(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID revisionId,
        UUID payerId,
        UUID ledgerEntryId,
        DenialKind denialKind,
        String sourceReasonCode,
        String originalReasonText,
        String normalizedCategoryKey,
        CategoryMappingSource categoryMappingSource,
        UUID mappingRuleVersionId,
        Money deniedAmount,
        LocalDate noticeDate,
        LocalDate receivedDate,
        String payerReference,
        UUID sourceDocumentId,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    public record RecoveryTaskView(
        UUID id,
        UUID clinicId,
        UUID recoveryCaseId,
        RecoveryTaskKind taskKind,
        String title,
        String description,
        String requiredDocumentKey,
        UUID satisfiedDocumentId,
        RecoveryTaskStatus status,
        UUID assignedToUserId,
        LocalDate dueDate,
        String resolutionNote,
        UUID completedBy,
        Instant completedAt,
        long version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record AppealDraftView(
        UUID id,
        UUID clinicId,
        UUID recoveryCaseId,
        int versionNumber,
        AppealStatus status,
        String locale,
        String subjectLine,
        String bodyText,
        String factualBasisSummary,
        List<UUID> citedEvidenceIds,
        int unresolvedPlaceholderCount,
        UUID approvedBy,
        Instant approvedAt,
        String approvalNote,
        UUID rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        UUID createdBy,
        Instant createdAt
    ) {
        public AppealDraftView {
            citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
        }
    }

    public record AppealSubmissionView(
        UUID id,
        UUID clinicId,
        UUID recoveryCaseId,
        UUID appealDraftVersionId,
        String channelCode,
        String externalSubmissionRef,
        LocalDate occurredOn,
        Instant occurredAt,
        OccurrencePrecision occurrencePrecision,
        UUID evidenceDocumentId,
        String notes,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    public record RecoveryCaseSummary(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID revisionId,
        UUID payerId,
        UUID primaryDenialEventId,
        DenialCaseStatus caseStatus,
        RecoveryOutcome recoveryOutcome,
        ClaimPaymentStatus paymentStatus,
        UUID assignedToUserId,
        LocalDate dueDate,
        DueDateBasis dueDateBasis,
        UUID dueDateRuleVersionId,
        String dueDateSourceNote,
        Money deniedAmount,
        Money estimatedRecoverableAmount,
        EstimateBasis estimateBasis,
        String estimateNote,
        String closureReason,
        long version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record RecoveryCaseDetail(
        RecoveryCaseSummary summary,
        DenialEventView primaryDenial,
        ClaimLedgerSummary ledger,
        List<RecoveryTaskView> tasks,
        List<AppealDraftView> appeals,
        List<AppealSubmissionView> appealSubmissions
    ) {
        public RecoveryCaseDetail {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            appeals = appeals == null ? List.of() : List.copyOf(appeals);
            appealSubmissions = appealSubmissions == null ? List.of() : List.copyOf(appealSubmissions);
        }
    }
}
