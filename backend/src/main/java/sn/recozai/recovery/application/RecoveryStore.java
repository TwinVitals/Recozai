package sn.recozai.recovery.application;

import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.recovery.domain.RecoveryDomain.StoredLedgerEntry;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public interface RecoveryStore {
    record StoredDenialEvent(
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
        BigDecimal deniedAmount,
        String currency,
        int currencyScale,
        LocalDate noticeDate,
        LocalDate receivedDate,
        String payerReference,
        UUID sourceDocumentId,
        String idempotencyKey,
        String requestHash,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    record StoredRecoveryCase(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID revisionId,
        UUID payerId,
        UUID primaryDenialEventId,
        DenialCaseStatus caseStatus,
        RecoveryOutcome recoveryOutcome,
        UUID assignedToUserId,
        LocalDate dueDate,
        DueDateBasis dueDateBasis,
        UUID dueDateRuleVersionId,
        String dueDateSourceNote,
        BigDecimal estimatedRecoverableAmount,
        EstimateBasis estimateBasis,
        String estimateNote,
        String closureReason,
        long version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
    ) {}

    record StoredRecoveryTask(
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

    record StoredAppealDraft(
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
    ) {}

    record StoredAppealSubmission(
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
        String idempotencyKey,
        String requestHash,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    record PageResult<T>(List<T> items, long totalElements) {}

    List<StoredLedgerEntry> ledgerEntries(UUID clinicId, UUID claimId, UUID revisionId);
    Optional<StoredLedgerEntry> ledgerEntryByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey);
    void appendLedgerEntry(StoredLedgerEntry entry);

    List<StoredDenialEvent> denialEventsByClaim(UUID clinicId, UUID claimId);
    Optional<StoredDenialEvent> denialEvent(UUID clinicId, UUID id);
    Optional<StoredDenialEvent> denialEventByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey);
    void appendDenialEvent(StoredDenialEvent event);

    PageResult<StoredRecoveryCase> recoveryCases(UUID clinicId, UUID payerId, DenialCaseStatus status, int page);
    Optional<StoredRecoveryCase> recoveryCase(UUID clinicId, UUID id);
    Optional<StoredRecoveryCase> recoveryCaseByDenialEvent(UUID clinicId, UUID primaryDenialEventId);
    Optional<StoredRecoveryCase> lockRecoveryCase(UUID clinicId, UUID id);
    void createRecoveryCase(StoredRecoveryCase recoveryCase);
    void updateRecoveryCase(StoredRecoveryCase recoveryCase, long expectedVersion);

    List<StoredRecoveryTask> tasks(UUID clinicId, UUID recoveryCaseId);
    Optional<StoredRecoveryTask> task(UUID clinicId, UUID recoveryCaseId, UUID taskId);
    void createTask(StoredRecoveryTask task);
    void updateTask(StoredRecoveryTask task, long expectedVersion);

    List<StoredAppealDraft> appealDrafts(UUID clinicId, UUID recoveryCaseId);
    Optional<StoredAppealDraft> appealDraft(UUID clinicId, UUID recoveryCaseId, UUID draftId);
    int nextAppealVersionNumber(UUID clinicId, UUID recoveryCaseId);
    void createAppealDraft(StoredAppealDraft draft);
    void updateAppealDraftStatus(StoredAppealDraft updated);

    List<StoredAppealSubmission> appealSubmissions(UUID clinicId, UUID recoveryCaseId);
    Optional<StoredAppealSubmission> appealSubmissionByIdempotencyKey(UUID clinicId, UUID recoveryCaseId, String idempotencyKey);
    void appendAppealSubmission(StoredAppealSubmission submission);
}
