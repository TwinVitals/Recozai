package sn.recozai.recovery.application;

import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.recovery.application.RecoveryStore.*;
import sn.recozai.recovery.domain.RecoveryDomain;
import sn.recozai.recovery.domain.RecoveryDomain.*;
import sn.recozai.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class RecoveryService {
    public record ActiveClaimFact(
        UUID claimId,
        UUID clinicId,
        UUID payerId,
        String externalReference,
        String currency,
        int currencyScale,
        boolean submittedState,
        UUID activeRevisionId,
        int activeRevisionNumber,
        BigDecimal submittedAmount
    ) {}

    public interface ClaimPort {
        Optional<ActiveClaimFact> activeClaim(UUID clinicId, UUID claimId);
        Optional<ActiveClaimFact> lockActiveClaim(UUID clinicId, UUID claimId);
    }

    public interface PayerPolicyPort {
        Set<String> activeCategoryKeys(UUID clinicId);
        List<PayerRuleSnapshot> effectiveApprovedRules(UUID clinicId, UUID payerId, LocalDate onDate);
    }

    public interface DocumentValidationPort {
        boolean isCleanClaimDocument(UUID clinicId, UUID claimId, UUID documentId);
        boolean evidenceExistsInClinic(UUID clinicId, UUID evidenceVersionId);
    }

    public record RecordLedgerEntryCommand(
        LedgerEntryType entryType,
        String amount,
        LocalDate occurredOn,
        String payerReference,
        UUID sourceDocumentId,
        UUID reversesEntryId,
        String reason,
        String idempotencyKey
    ) {}

    public record RecordDenialCommand(
        DenialKind denialKind,
        String sourceReasonCode,
        String originalReasonText,
        String normalizedCategoryKey,
        String deniedAmount,
        LocalDate noticeDate,
        LocalDate receivedDate,
        String payerReference,
        UUID sourceDocumentId,
        LocalDate staffConfirmedDueDate,
        String dueDateSourceNote,
        String idempotencyKey
    ) {}

    public record UpdateRecoveryCaseCommand(
        DenialCaseStatus caseStatus,
        RecoveryOutcome recoveryOutcome,
        UUID assignedToUserId,
        LocalDate staffConfirmedDueDate,
        String dueDateSourceNote,
        String estimatedRecoverableAmount,
        EstimateBasis estimateBasis,
        String estimateNote,
        String closureReason
    ) {}

    public record CreateTaskCommand(
        RecoveryTaskKind taskKind,
        String title,
        String description,
        String requiredDocumentKey,
        UUID assignedToUserId,
        LocalDate dueDate
    ) {}

    public record UpdateTaskCommand(
        RecoveryTaskStatus status,
        UUID assignedToUserId,
        LocalDate dueDate,
        UUID satisfiedDocumentId,
        String resolutionNote
    ) {}

    public record CreateAppealDraftCommand(
        String locale,
        String subjectLine,
        String bodyText,
        String factualBasisSummary,
        List<UUID> citedEvidenceIds,
        boolean submitForApproval
    ) {}

    public record ReviewAppealCommand(
        boolean approved,
        String note
    ) {}

    public record RecordAppealSubmissionCommand(
        UUID appealDraftVersionId,
        String channelCode,
        String externalSubmissionRef,
        LocalDate occurredOn,
        Instant occurredAt,
        OccurrencePrecision occurrencePrecision,
        UUID evidenceDocumentId,
        String notes,
        String idempotencyKey
    ) {}

    private final RecoveryStore store;
    private final ClaimPort claims;
    private final PayerPolicyPort payers;
    private final DocumentValidationPort documents;
    private final Transactions transactions;
    private final Access access;
    private final Audit audit;
    private final Clock clock;

    public RecoveryService(RecoveryStore store,
                           ClaimPort claims,
                           PayerPolicyPort payers,
                           DocumentValidationPort documents,
                           Transactions transactions,
                           Access access,
                           Audit audit,
                           Clock clock) {
        this.store = store;
        this.claims = claims;
        this.payers = payers;
        this.documents = documents;
        this.transactions = transactions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public ClaimLedgerSummary claimLedger(Actor actor, UUID claimId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireLedgerRead();
            ActiveClaimFact claim = claims.activeClaim(current.clinicId(), claimId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            return buildLedgerSummary(claim);
        });
    }

    public LedgerEntryView recordLedgerEntry(Actor actor, UUID claimId, RecordLedgerEntryCommand cmd) {
        if (cmd == null || cmd.entryType() == null) {
            throw new Failure(422, "INVALID_LEDGER_ENTRY");
        }
        if (cmd.entryType() == LedgerEntryType.DENIAL_DISPUTED) {
            throw new Failure(422, "USE_DENIAL_EVENT_ENDPOINT");
        }
        String idempotencyKey = RecoveryDomain.validateIdempotencyKey(cmd.idempotencyKey());
        String requestHash = computeLedgerRequestHash(cmd);

        return transactions.execute(() -> {
            Actor current = access.current(actor);
            if (cmd.entryType() == LedgerEntryType.WRITE_OFF) {
                current.requireWriteOffApproval();
            } else {
                current.requireLedgerWrite();
            }

            ActiveClaimFact claim = claims.lockActiveClaim(current.clinicId(), claimId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            Optional<StoredLedgerEntry> existingByKey = store.ledgerEntryByIdempotencyKey(
                current.clinicId(), claimId, idempotencyKey
            );
            if (existingByKey.isPresent()) {
                StoredLedgerEntry prior = existingByKey.get();
                if (!prior.requestHash().equals(requestHash)) {
                    throw new Failure(409, "IDEMPOTENCY_KEY_CONFLICT");
                }
                List<StoredLedgerEntry> all = store.ledgerEntries(
                    current.clinicId(), claimId, claim.activeRevisionId()
                );
                DerivedLedgerBalance bal = RecoveryDomain.deriveLedgerBalance(
                    claim.submittedAmount(), claim.currencyScale(), all
                );
                return toLedgerView(prior, bal.reversedEntryIds().contains(prior.id()));
            }

            if (!claim.submittedState()) {
                throw new Failure(409, "CLAIM_NOT_SUBMITTED");
            }
            if (cmd.sourceDocumentId() != null
                && !documents.isCleanClaimDocument(current.clinicId(), claimId, cmd.sourceDocumentId())) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }

            List<StoredLedgerEntry> existingEntries = store.ledgerEntries(
                current.clinicId(), claimId, claim.activeRevisionId()
            );
            ValidatedLedgerTransition validated = RecoveryDomain.validateLedgerEntry(
                claim.submittedAmount(),
                claim.currencyScale(),
                existingEntries,
                cmd.entryType(),
                cmd.amount(),
                cmd.occurredOn(),
                cmd.payerReference(),
                cmd.reversesEntryId(),
                cmd.reason()
            );

            Instant now = clock.instant();
            StoredLedgerEntry created = new StoredLedgerEntry(
                UUID.randomUUID(),
                current.clinicId(),
                claim.claimId(),
                claim.activeRevisionId(),
                cmd.entryType(),
                validated.amount(),
                validated.previousOutstandingAmount(),
                validated.resultingOutstandingAmount(),
                claim.currency(),
                claim.currencyScale(),
                cmd.occurredOn(),
                validated.payerReference(),
                cmd.sourceDocumentId(),
                cmd.reversesEntryId(),
                validated.reason(),
                idempotencyKey,
                requestHash,
                current.userId(),
                now
            );
            store.appendLedgerEntry(created);

            String auditAction = switch (cmd.entryType()) {
                case WRITE_OFF -> "LEDGER_WRITE_OFF_RECORDED";
                case REVERSAL -> "LEDGER_REVERSAL_RECORDED";
                case RECOVERY_PAYMENT -> "LEDGER_RECOVERY_PAYMENT_RECORDED";
                default -> "LEDGER_ENTRY_RECORDED";
            };
            audit.record(current.userId(), current.clinicId(), auditAction, created.id());
            return toLedgerView(created, false);
        });
    }

    public RecoveryCaseDetail recordDenialEvent(Actor actor, UUID claimId, RecordDenialCommand cmd) {
        if (cmd == null || cmd.denialKind() == null || cmd.noticeDate() == null || cmd.receivedDate() == null) {
            throw new Failure(422, "INVALID_DENIAL_EVENT");
        }
        if (cmd.receivedDate().isBefore(cmd.noticeDate())) {
            throw new Failure(422, "INVALID_DENIAL_DATES");
        }
        String originalReason = RecoveryDomain.validateRequiredText(
            cmd.originalReasonText(), 2000, "ORIGINAL_REASON_TEXT_REQUIRED"
        );
        String sourceReasonCode = RecoveryDomain.validateOptionalText(
            cmd.sourceReasonCode(), 80, "INVALID_DENIAL_EVENT"
        );
        String payerReference = RecoveryDomain.validateOptionalText(
            cmd.payerReference(), 120, "INVALID_DENIAL_EVENT"
        );
        String idempotencyKey = RecoveryDomain.validateIdempotencyKey(cmd.idempotencyKey());
        String requestHash = computeDenialRequestHash(cmd, originalReason, sourceReasonCode, payerReference);

        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();

            ActiveClaimFact claim = claims.lockActiveClaim(current.clinicId(), claimId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            Optional<StoredDenialEvent> existingDenial = store.denialEventByIdempotencyKey(
                current.clinicId(), claimId, idempotencyKey
            );
            if (existingDenial.isPresent()) {
                StoredDenialEvent prior = existingDenial.get();
                if (!prior.requestHash().equals(requestHash)) {
                    throw new Failure(409, "IDEMPOTENCY_KEY_CONFLICT");
                }
                StoredRecoveryCase existingCase = store.recoveryCaseByDenialEvent(current.clinicId(), prior.id())
                    .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
                return buildCaseDetail(current.clinicId(), existingCase, claim);
            }

            if (!claim.submittedState()) {
                throw new Failure(409, "CLAIM_NOT_SUBMITTED");
            }
            if (cmd.sourceDocumentId() != null
                && !documents.isCleanClaimDocument(current.clinicId(), claimId, cmd.sourceDocumentId())) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }

            Set<String> activeCategories = payers.activeCategoryKeys(current.clinicId());
            List<PayerRuleSnapshot> effectiveRules = payers.effectiveApprovedRules(
                current.clinicId(), claim.payerId(), cmd.receivedDate()
            );
            ResolvedCategory resolvedCategory = RecoveryDomain.resolveCategory(
                sourceReasonCode, cmd.normalizedCategoryKey(), effectiveRules, activeCategories
            );
            ResolvedDueDate resolvedDueDate = RecoveryDomain.resolveInitialDueDate(
                cmd.noticeDate(), cmd.receivedDate(), effectiveRules,
                cmd.staffConfirmedDueDate(), cmd.dueDateSourceNote()
            );

            List<StoredLedgerEntry> existingLedger = store.ledgerEntries(
                current.clinicId(), claimId, claim.activeRevisionId()
            );
            ValidatedLedgerTransition ledgerTransition = RecoveryDomain.validateLedgerEntry(
                claim.submittedAmount(),
                claim.currencyScale(),
                existingLedger,
                LedgerEntryType.DENIAL_DISPUTED,
                cmd.deniedAmount(),
                cmd.receivedDate(),
                payerReference,
                null,
                "Denial recorded: " + resolvedCategory.categoryKey()
            );
            if (cmd.denialKind() == DenialKind.FULL
                && ledgerTransition.amount().compareTo(claim.submittedAmount()) != 0
                && existingLedger.isEmpty()) {
                throw new Failure(422, "FULL_DENIAL_AMOUNT_MISMATCH");
            }

            Instant now = clock.instant();
            StoredLedgerEntry denialLedgerEntry = new StoredLedgerEntry(
                UUID.randomUUID(),
                current.clinicId(),
                claim.claimId(),
                claim.activeRevisionId(),
                LedgerEntryType.DENIAL_DISPUTED,
                ledgerTransition.amount(),
                ledgerTransition.previousOutstandingAmount(),
                ledgerTransition.resultingOutstandingAmount(),
                claim.currency(),
                claim.currencyScale(),
                cmd.receivedDate(),
                payerReference,
                cmd.sourceDocumentId(),
                null,
                ledgerTransition.reason(),
                "denial-ledger:" + idempotencyKey,
                requestHash,
                current.userId(),
                now
            );
            store.appendLedgerEntry(denialLedgerEntry);

            StoredDenialEvent denialEvent = new StoredDenialEvent(
                UUID.randomUUID(),
                current.clinicId(),
                claim.claimId(),
                claim.activeRevisionId(),
                claim.payerId(),
                denialLedgerEntry.id(),
                cmd.denialKind(),
                sourceReasonCode,
                originalReason,
                resolvedCategory.categoryKey(),
                resolvedCategory.source(),
                resolvedCategory.mappingRuleVersionId(),
                ledgerTransition.amount(),
                claim.currency(),
                claim.currencyScale(),
                cmd.noticeDate(),
                cmd.receivedDate(),
                payerReference,
                cmd.sourceDocumentId(),
                idempotencyKey,
                requestHash,
                current.userId(),
                now
            );
            store.appendDenialEvent(denialEvent);

            StoredRecoveryCase recoveryCase = new StoredRecoveryCase(
                UUID.randomUUID(),
                current.clinicId(),
                claim.claimId(),
                claim.activeRevisionId(),
                claim.payerId(),
                denialEvent.id(),
                DenialCaseStatus.NEW,
                RecoveryOutcome.PENDING,
                null,
                resolvedDueDate.dueDate(),
                resolvedDueDate.basis(),
                resolvedDueDate.ruleVersionId(),
                resolvedDueDate.sourceNote(),
                null,
                EstimateBasis.UNESTIMATED,
                null,
                null,
                0L,
                current.userId(),
                now,
                now
            );
            store.createRecoveryCase(recoveryCase);

            seedInitialTasksFromRules(current.clinicId(), recoveryCase.id(), effectiveRules, current.userId(), now);

            audit.record(current.userId(), current.clinicId(), "DENIAL_EVENT_RECORDED", denialEvent.id());
            audit.record(current.userId(), current.clinicId(), "RECOVERY_CASE_OPENED", recoveryCase.id());

            return buildCaseDetail(current.clinicId(), recoveryCase, claim);
        });
    }

    public Page<RecoveryCaseSummary> recoveryCases(Actor actor,
                                                   UUID payerId,
                                                   DenialCaseStatus status,
                                                   int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialRead();
            var result = store.recoveryCases(current.clinicId(), payerId, status, page);
            List<RecoveryCaseSummary> items = result.items().stream()
                .map(c -> {
                    ActiveClaimFact claim = claims.activeClaim(current.clinicId(), c.claimId())
                        .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
                    StoredDenialEvent denial = store.denialEvent(current.clinicId(), c.primaryDenialEventId())
                        .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
                    DerivedLedgerBalance bal = RecoveryDomain.deriveLedgerBalance(
                        claim.submittedAmount(),
                        claim.currencyScale(),
                        store.ledgerEntries(current.clinicId(), c.claimId(), c.revisionId())
                    );
                    return toCaseSummary(c, denial, bal.paymentStatus(), claim.currency(), claim.currencyScale());
                })
                .toList();
            return new Page<>(items, page, 25, result.totalElements());
        });
    }

    public RecoveryCaseDetail recoveryCase(Actor actor, UUID caseId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialRead();
            StoredRecoveryCase c = store.recoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            ActiveClaimFact claim = claims.activeClaim(current.clinicId(), c.claimId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            return buildCaseDetail(current.clinicId(), c, claim);
        });
    }

    public RecoveryCaseDetail updateRecoveryCase(Actor actor,
                                                 UUID caseId,
                                                 UpdateRecoveryCaseCommand cmd,
                                                 long expectedVersion) {
        if (cmd == null) throw new Failure(422, "INVALID_RECOVERY_CASE_UPDATE");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();
            StoredRecoveryCase existing = store.lockRecoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            ActiveClaimFact claim = claims.activeClaim(current.clinicId(), existing.claimId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            StoredDenialEvent denial = store.denialEvent(current.clinicId(), existing.primaryDenialEventId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            List<StoredAppealDraft> appeals = store.appealDrafts(current.clinicId(), caseId);
            boolean hasApprovedAppeal = appeals.stream().anyMatch(
                a -> a.status() == AppealStatus.APPROVED || a.status() == AppealStatus.SUBMITTED
            );
            boolean hasAppealSubmission = !store.appealSubmissions(current.clinicId(), caseId).isEmpty();

            DenialCaseStatus targetStatus = cmd.caseStatus() != null ? cmd.caseStatus() : existing.caseStatus();
            RecoveryOutcome targetOutcome = cmd.recoveryOutcome() != null
                ? cmd.recoveryOutcome()
                : (targetStatus == DenialCaseStatus.CLOSED ? existing.recoveryOutcome() : RecoveryOutcome.PENDING);
            String closureReason = targetStatus == DenialCaseStatus.CLOSED
                ? RecoveryDomain.validateReason(
                    cmd.closureReason() != null ? cmd.closureReason() : existing.closureReason(),
                    "CLOSURE_REASON_REQUIRED"
                )
                : null;

            RecoveryDomain.validateCaseTransition(
                existing.caseStatus(), targetStatus, targetOutcome,
                closureReason, hasApprovedAppeal, hasAppealSubmission
            );

            LocalDate dueDate = existing.dueDate();
            DueDateBasis dueDateBasis = existing.dueDateBasis();
            UUID dueDateRuleId = existing.dueDateRuleVersionId();
            String dueDateNote = existing.dueDateSourceNote();
            if (cmd.staffConfirmedDueDate() != null) {
                dueDate = cmd.staffConfirmedDueDate();
                dueDateBasis = DueDateBasis.STAFF_CONFIRMED;
                dueDateRuleId = null;
                dueDateNote = RecoveryDomain.validateRequiredText(
                    cmd.dueDateSourceNote(), 240, "DUE_DATE_SOURCE_NOTE_REQUIRED"
                );
            }

            BigDecimal estimatedAmount = existing.estimatedRecoverableAmount();
            EstimateBasis estimateBasis = existing.estimateBasis();
            String estimateNote = existing.estimateNote();
            if (cmd.estimateBasis() != null || cmd.estimatedRecoverableAmount() != null) {
                EstimateBasis requestedBasis = cmd.estimateBasis() != null
                    ? cmd.estimateBasis()
                    : EstimateBasis.STAFF_CONFIRMED;
                if (requestedBasis == EstimateBasis.UNESTIMATED) {
                    estimatedAmount = null;
                    estimateBasis = EstimateBasis.UNESTIMATED;
                    estimateNote = null;
                } else {
                    estimatedAmount = RecoveryDomain.parseNonNegativeAmount(
                        cmd.estimatedRecoverableAmount(), claim.currencyScale()
                    );
                    if (estimatedAmount.compareTo(denial.deniedAmount()) > 0) {
                        throw new Failure(422, "ESTIMATE_EXCEEDS_DENIED_AMOUNT");
                    }
                    estimateBasis = requestedBasis;
                    estimateNote = RecoveryDomain.validateRequiredText(
                        cmd.estimateNote(), 240, "ESTIMATE_NOTE_REQUIRED"
                    );
                }
            }

            Instant now = clock.instant();
            StoredRecoveryCase updated = new StoredRecoveryCase(
                existing.id(),
                existing.clinicId(),
                existing.claimId(),
                existing.revisionId(),
                existing.payerId(),
                existing.primaryDenialEventId(),
                targetStatus,
                targetOutcome,
                cmd.assignedToUserId() != null ? cmd.assignedToUserId() : existing.assignedToUserId(),
                dueDate,
                dueDateBasis,
                dueDateRuleId,
                dueDateNote,
                estimatedAmount,
                estimateBasis,
                estimateNote,
                closureReason,
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.updateRecoveryCase(updated, expectedVersion);
            audit.record(current.userId(), current.clinicId(), "RECOVERY_CASE_UPDATED", updated.id());
            return buildCaseDetail(current.clinicId(), updated, claim);
        });
    }

    public RecoveryTaskView createTask(Actor actor, UUID caseId, CreateTaskCommand cmd) {
        if (cmd == null || cmd.taskKind() == null) {
            throw new Failure(422, "INVALID_RECOVERY_TASK");
        }
        String title = RecoveryDomain.validateRequiredText(cmd.title(), 160, "INVALID_RECOVERY_TASK");
        String description = RecoveryDomain.validateOptionalText(cmd.description(), 500, "INVALID_RECOVERY_TASK");
        String requiredDocKey = RecoveryDomain.validateOptionalText(cmd.requiredDocumentKey(), 80, "INVALID_RECOVERY_TASK");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();
            store.recoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            Instant now = clock.instant();
            StoredRecoveryTask task = new StoredRecoveryTask(
                UUID.randomUUID(),
                current.clinicId(),
                caseId,
                cmd.taskKind(),
                title,
                description,
                requiredDocKey,
                null,
                RecoveryTaskStatus.OPEN,
                cmd.assignedToUserId(),
                cmd.dueDate(),
                null,
                null,
                null,
                0L,
                current.userId(),
                now,
                now
            );
            store.createTask(task);
            audit.record(current.userId(), current.clinicId(), "RECOVERY_TASK_CREATED", task.id());
            return toTaskView(task);
        });
    }

    public RecoveryTaskView updateTask(Actor actor,
                                       UUID caseId,
                                       UUID taskId,
                                       UpdateTaskCommand cmd,
                                       long expectedVersion) {
        if (cmd == null || cmd.status() == null) {
            throw new Failure(422, "INVALID_RECOVERY_TASK");
        }
        String resolutionNote = RecoveryDomain.validateOptionalText(cmd.resolutionNote(), 240, "INVALID_RECOVERY_TASK");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();
            StoredRecoveryCase rc = store.recoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            StoredRecoveryTask existing = store.task(current.clinicId(), caseId, taskId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            UUID satisfiedDocId = cmd.satisfiedDocumentId() != null
                ? cmd.satisfiedDocumentId()
                : existing.satisfiedDocumentId();
            if (satisfiedDocId != null
                && !documents.isCleanClaimDocument(current.clinicId(), rc.claimId(), satisfiedDocId)) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }
            if (cmd.status() == RecoveryTaskStatus.COMPLETED
                && existing.requiredDocumentKey() != null
                && satisfiedDocId == null) {
                throw new Failure(422, "REQUIRED_DOCUMENT_NOT_ATTACHED");
            }

            Instant now = clock.instant();
            UUID completedBy = cmd.status() == RecoveryTaskStatus.COMPLETED ? current.userId() : null;
            Instant completedAt = cmd.status() == RecoveryTaskStatus.COMPLETED ? now : null;
            StoredRecoveryTask updated = new StoredRecoveryTask(
                existing.id(),
                existing.clinicId(),
                existing.recoveryCaseId(),
                existing.taskKind(),
                existing.title(),
                existing.description(),
                existing.requiredDocumentKey(),
                satisfiedDocId,
                cmd.status(),
                cmd.assignedToUserId() != null ? cmd.assignedToUserId() : existing.assignedToUserId(),
                cmd.dueDate() != null ? cmd.dueDate() : existing.dueDate(),
                resolutionNote,
                completedBy,
                completedAt,
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.updateTask(updated, expectedVersion);
            audit.record(current.userId(), current.clinicId(), "RECOVERY_TASK_UPDATED", updated.id());
            return toTaskView(updated);
        });
    }

    public AppealDraftView createAppealDraft(Actor actor, UUID caseId, CreateAppealDraftCommand cmd) {
        if (cmd == null) throw new Failure(422, "INVALID_APPEAL_DRAFT");
        String locale = cmd.locale() == null ? "fr-SN" : cmd.locale().strip();
        if (!"fr-SN".equals(locale) && !"en".equals(locale)) {
            throw new Failure(422, "INVALID_APPEAL_DRAFT");
        }
        String subject = RecoveryDomain.validateRequiredText(cmd.subjectLine(), 200, "INVALID_APPEAL_DRAFT");
        String body = RecoveryDomain.validateRequiredText(cmd.bodyText(), 8000, "INVALID_APPEAL_DRAFT");
        String factualBasis = RecoveryDomain.validateRequiredText(cmd.factualBasisSummary(), 1000, "INVALID_APPEAL_DRAFT");
        List<UUID> citedEvidence = cmd.citedEvidenceIds() == null ? List.of() : List.copyOf(cmd.citedEvidenceIds());
        int placeholderCount = RecoveryDomain.countUnresolvedPlaceholders(subject, body, factualBasis);

        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();
            StoredRecoveryCase rc = store.lockRecoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (rc.caseStatus() == DenialCaseStatus.CLOSED) {
                throw new Failure(409, "RECOVERY_CASE_ALREADY_CLOSED");
            }
            for (UUID evidenceId : citedEvidence) {
                if (evidenceId == null || !documents.evidenceExistsInClinic(current.clinicId(), evidenceId)) {
                    throw new Failure(422, "INVALID_CITED_EVIDENCE");
                }
            }

            List<StoredAppealDraft> priorDrafts = store.appealDrafts(current.clinicId(), caseId);
            for (StoredAppealDraft prior : priorDrafts) {
                if (prior.status() == AppealStatus.DRAFT || prior.status() == AppealStatus.PENDING_APPROVAL) {
                    store.updateAppealDraftStatus(new StoredAppealDraft(
                        prior.id(), prior.clinicId(), prior.recoveryCaseId(), prior.versionNumber(),
                        AppealStatus.SUPERSEDED, prior.locale(), prior.subjectLine(), prior.bodyText(),
                        prior.factualBasisSummary(), prior.citedEvidenceIds(), prior.unresolvedPlaceholderCount(),
                        prior.approvedBy(), prior.approvedAt(), prior.approvalNote(),
                        prior.rejectedBy(), prior.rejectedAt(), prior.rejectionReason(),
                        prior.createdBy(), prior.createdAt()
                    ));
                }
            }

            int nextVersion = store.nextAppealVersionNumber(current.clinicId(), caseId);
            AppealStatus initialStatus = cmd.submitForApproval()
                ? AppealStatus.PENDING_APPROVAL
                : AppealStatus.DRAFT;
            Instant now = clock.instant();
            StoredAppealDraft created = new StoredAppealDraft(
                UUID.randomUUID(),
                current.clinicId(),
                caseId,
                nextVersion,
                initialStatus,
                locale,
                subject,
                body,
                factualBasis,
                citedEvidence,
                placeholderCount,
                null,
                null,
                null,
                null,
                null,
                null,
                current.userId(),
                now
            );
            store.createAppealDraft(created);

            DenialCaseStatus nextCaseStatus = cmd.submitForApproval()
                ? DenialCaseStatus.AWAITING_APPROVAL
                : DenialCaseStatus.APPEAL_DRAFT;
            if (rc.caseStatus() != nextCaseStatus) {
                store.updateRecoveryCase(new StoredRecoveryCase(
                    rc.id(), rc.clinicId(), rc.claimId(), rc.revisionId(), rc.payerId(),
                    rc.primaryDenialEventId(), nextCaseStatus, rc.recoveryOutcome(),
                    rc.assignedToUserId(), rc.dueDate(), rc.dueDateBasis(),
                    rc.dueDateRuleVersionId(), rc.dueDateSourceNote(),
                    rc.estimatedRecoverableAmount(), rc.estimateBasis(), rc.estimateNote(),
                    rc.closureReason(), rc.version() + 1, rc.createdBy(), rc.createdAt(), now
                ), rc.version());
            }

            audit.record(current.userId(), current.clinicId(), "APPEAL_DRAFT_CREATED", created.id());
            return toAppealDraftView(created);
        });
    }

    public AppealDraftView reviewAppealDraft(Actor actor,
                                             UUID caseId,
                                             UUID draftId,
                                             ReviewAppealCommand cmd) {
        if (cmd == null) throw new Failure(422, "INVALID_APPEAL_REVIEW");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireAppealApproval();
            StoredRecoveryCase rc = store.lockRecoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            StoredAppealDraft draft = store.appealDraft(current.clinicId(), caseId, draftId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            if (draft.status() != AppealStatus.DRAFT && draft.status() != AppealStatus.PENDING_APPROVAL) {
                throw new Failure(409, "APPEAL_DRAFT_NOT_REVIEWABLE");
            }

            Instant now = clock.instant();
            if (cmd.approved()) {
                if (draft.unresolvedPlaceholderCount() > 0) {
                    throw new Failure(422, "UNRESOLVED_APPEAL_PLACEHOLDERS");
                }
                String approvalNote = RecoveryDomain.validateOptionalText(cmd.note(), 240, "INVALID_APPEAL_REVIEW");
                for (StoredAppealDraft other : store.appealDrafts(current.clinicId(), caseId)) {
                    if (!other.id().equals(draft.id()) && other.status() == AppealStatus.APPROVED) {
                        store.updateAppealDraftStatus(new StoredAppealDraft(
                            other.id(), other.clinicId(), other.recoveryCaseId(), other.versionNumber(),
                            AppealStatus.SUPERSEDED, other.locale(), other.subjectLine(), other.bodyText(),
                            other.factualBasisSummary(), other.citedEvidenceIds(), other.unresolvedPlaceholderCount(),
                            other.approvedBy(), other.approvedAt(), other.approvalNote(),
                            other.rejectedBy(), other.rejectedAt(), other.rejectionReason(),
                            other.createdBy(), other.createdAt()
                        ));
                    }
                }
                StoredAppealDraft approved = new StoredAppealDraft(
                    draft.id(), draft.clinicId(), draft.recoveryCaseId(), draft.versionNumber(),
                    AppealStatus.APPROVED, draft.locale(), draft.subjectLine(), draft.bodyText(),
                    draft.factualBasisSummary(), draft.citedEvidenceIds(), draft.unresolvedPlaceholderCount(),
                    current.userId(), now, approvalNote,
                    null, null, null,
                    draft.createdBy(), draft.createdAt()
                );
                store.updateAppealDraftStatus(approved);
                store.updateRecoveryCase(new StoredRecoveryCase(
                    rc.id(), rc.clinicId(), rc.claimId(), rc.revisionId(), rc.payerId(),
                    rc.primaryDenialEventId(), DenialCaseStatus.APPROVED_FOR_SUBMISSION, rc.recoveryOutcome(),
                    rc.assignedToUserId(), rc.dueDate(), rc.dueDateBasis(),
                    rc.dueDateRuleVersionId(), rc.dueDateSourceNote(),
                    rc.estimatedRecoverableAmount(), rc.estimateBasis(), rc.estimateNote(),
                    rc.closureReason(), rc.version() + 1, rc.createdBy(), rc.createdAt(), now
                ), rc.version());
                audit.record(current.userId(), current.clinicId(), "APPEAL_DRAFT_APPROVED", approved.id());
                return toAppealDraftView(approved);
            } else {
                String rejectionReason = RecoveryDomain.validateReason(cmd.note(), "REJECTION_REASON_REQUIRED");
                StoredAppealDraft rejected = new StoredAppealDraft(
                    draft.id(), draft.clinicId(), draft.recoveryCaseId(), draft.versionNumber(),
                    AppealStatus.REJECTED, draft.locale(), draft.subjectLine(), draft.bodyText(),
                    draft.factualBasisSummary(), draft.citedEvidenceIds(), draft.unresolvedPlaceholderCount(),
                    null, null, null,
                    current.userId(), now, rejectionReason,
                    draft.createdBy(), draft.createdAt()
                );
                store.updateAppealDraftStatus(rejected);
                store.updateRecoveryCase(new StoredRecoveryCase(
                    rc.id(), rc.clinicId(), rc.claimId(), rc.revisionId(), rc.payerId(),
                    rc.primaryDenialEventId(), DenialCaseStatus.ACTION_REQUIRED, rc.recoveryOutcome(),
                    rc.assignedToUserId(), rc.dueDate(), rc.dueDateBasis(),
                    rc.dueDateRuleVersionId(), rc.dueDateSourceNote(),
                    rc.estimatedRecoverableAmount(), rc.estimateBasis(), rc.estimateNote(),
                    rc.closureReason(), rc.version() + 1, rc.createdBy(), rc.createdAt(), now
                ), rc.version());
                audit.record(current.userId(), current.clinicId(), "APPEAL_DRAFT_REJECTED", rejected.id());
                return toAppealDraftView(rejected);
            }
        });
    }

    public AppealSubmissionView recordAppealSubmission(Actor actor,
                                                       UUID caseId,
                                                       RecordAppealSubmissionCommand cmd) {
        if (cmd == null || cmd.appealDraftVersionId() == null || cmd.occurredOn() == null || cmd.occurrencePrecision() == null) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        if ((cmd.occurrencePrecision() == OccurrencePrecision.INSTANT) != (cmd.occurredAt() != null)) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        String channelCode = RecoveryDomain.normalizeChannelCode(cmd.channelCode());
        String externalRef = RecoveryDomain.validateRequiredText(
            cmd.externalSubmissionRef(), 120, "INVALID_SUBMISSION_EVENT"
        );
        String notes = RecoveryDomain.validateOptionalText(cmd.notes(), 240, "INVALID_SUBMISSION_EVENT");
        String idempotencyKey = RecoveryDomain.validateIdempotencyKey(cmd.idempotencyKey());
        String requestHash = computeAppealSubmissionHash(cmd, channelCode, externalRef, notes);

        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireDenialWrite();
            StoredRecoveryCase rc = store.lockRecoveryCase(current.clinicId(), caseId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            Optional<StoredAppealSubmission> existingByKey = store.appealSubmissionByIdempotencyKey(
                current.clinicId(), caseId, idempotencyKey
            );
            if (existingByKey.isPresent()) {
                StoredAppealSubmission prior = existingByKey.get();
                if (!prior.requestHash().equals(requestHash)) {
                    throw new Failure(409, "IDEMPOTENCY_KEY_CONFLICT");
                }
                return toAppealSubmissionView(prior);
            }

            StoredAppealDraft draft = store.appealDraft(current.clinicId(), caseId, cmd.appealDraftVersionId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (draft.status() != AppealStatus.APPROVED) {
                throw new Failure(409, "APPEAL_DRAFT_NOT_APPROVED");
            }
            if (cmd.evidenceDocumentId() != null
                && !documents.isCleanClaimDocument(current.clinicId(), rc.claimId(), cmd.evidenceDocumentId())) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }

            Instant now = clock.instant();
            StoredAppealSubmission submission = new StoredAppealSubmission(
                UUID.randomUUID(),
                current.clinicId(),
                caseId,
                draft.id(),
                channelCode,
                externalRef,
                cmd.occurredOn(),
                cmd.occurredAt(),
                cmd.occurrencePrecision(),
                cmd.evidenceDocumentId(),
                notes,
                idempotencyKey,
                requestHash,
                current.userId(),
                now
            );
            store.appendAppealSubmission(submission);

            store.updateAppealDraftStatus(new StoredAppealDraft(
                draft.id(), draft.clinicId(), draft.recoveryCaseId(), draft.versionNumber(),
                AppealStatus.SUBMITTED, draft.locale(), draft.subjectLine(), draft.bodyText(),
                draft.factualBasisSummary(), draft.citedEvidenceIds(), draft.unresolvedPlaceholderCount(),
                draft.approvedBy(), draft.approvedAt(), draft.approvalNote(),
                draft.rejectedBy(), draft.rejectedAt(), draft.rejectionReason(),
                draft.createdBy(), draft.createdAt()
            ));
            store.updateRecoveryCase(new StoredRecoveryCase(
                rc.id(), rc.clinicId(), rc.claimId(), rc.revisionId(), rc.payerId(),
                rc.primaryDenialEventId(), DenialCaseStatus.SUBMITTED_TO_PAYER, rc.recoveryOutcome(),
                rc.assignedToUserId(), rc.dueDate(), rc.dueDateBasis(),
                rc.dueDateRuleVersionId(), rc.dueDateSourceNote(),
                rc.estimatedRecoverableAmount(), rc.estimateBasis(), rc.estimateNote(),
                rc.closureReason(), rc.version() + 1, rc.createdBy(), rc.createdAt(), now
            ), rc.version());

            audit.record(current.userId(), current.clinicId(), "APPEAL_SUBMITTED_TO_PAYER", submission.id());
            return toAppealSubmissionView(submission);
        });
    }

    private void seedInitialTasksFromRules(UUID clinicId,
                                           UUID recoveryCaseId,
                                           List<PayerRuleSnapshot> effectiveRules,
                                           UUID createdBy,
                                           Instant now) {
        if (effectiveRules == null) return;
        for (PayerRuleSnapshot rule : effectiveRules) {
            if (rule.workflowSteps() != null) {
                for (String step : rule.workflowSteps()) {
                    RecoveryTaskKind kind = parseTaskKind(step);
                    store.createTask(new StoredRecoveryTask(
                        UUID.randomUUID(), clinicId, recoveryCaseId, kind,
                        step, null, null, null, RecoveryTaskStatus.OPEN,
                        null, null, null, null, null, 0L, createdBy, now, now
                    ));
                }
            }
            if (rule.requiredDocumentKeys() != null) {
                for (String docKey : rule.requiredDocumentKeys()) {
                    store.createTask(new StoredRecoveryTask(
                        UUID.randomUUID(), clinicId, recoveryCaseId,
                        RecoveryTaskKind.COLLECT_SUPPORTING_DOCUMENTS,
                        "DOCUMENT:" + docKey, null, docKey, null,
                        RecoveryTaskStatus.OPEN, null, null, null, null, null,
                        0L, createdBy, now, now
                    ));
                }
            }
        }
    }

    private static RecoveryTaskKind parseTaskKind(String step) {
        try {
            return RecoveryTaskKind.valueOf(step);
        } catch (IllegalArgumentException error) {
            return RecoveryTaskKind.CUSTOM_FOLLOW_UP;
        }
    }

    private ClaimLedgerSummary buildLedgerSummary(ActiveClaimFact claim) {
        List<StoredLedgerEntry> entries = store.ledgerEntries(
            claim.clinicId(), claim.claimId(), claim.activeRevisionId()
        );
        DerivedLedgerBalance bal = RecoveryDomain.deriveLedgerBalance(
            claim.submittedAmount(), claim.currencyScale(), entries
        );
        List<LedgerEntryView> entryViews = entries.stream()
            .map(e -> toLedgerView(e, bal.reversedEntryIds().contains(e.id())))
            .toList();
        return new ClaimLedgerSummary(
            claim.claimId(),
            claim.clinicId(),
            claim.activeRevisionId(),
            claim.activeRevisionNumber(),
            claim.currency(),
            claim.currencyScale(),
            RecoveryDomain.toMoney(bal.submittedAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.initialPaidAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.contractualAdjustmentAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.deniedDisputedAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.recoveredAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.writtenOffAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.outstandingAmount(), claim.currency(), claim.currencyScale()),
            RecoveryDomain.toMoney(bal.openDisputedAmount(), claim.currency(), claim.currencyScale()),
            bal.paymentStatus(),
            entryViews
        );
    }

    private RecoveryCaseDetail buildCaseDetail(UUID clinicId, StoredRecoveryCase c, ActiveClaimFact claim) {
        StoredDenialEvent denial = store.denialEvent(clinicId, c.primaryDenialEventId())
            .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
        ClaimLedgerSummary ledger = buildLedgerSummary(claim);
        RecoveryCaseSummary summary = toCaseSummary(
            c, denial, ledger.paymentStatus(), claim.currency(), claim.currencyScale()
        );
        List<RecoveryTaskView> tasks = store.tasks(clinicId, c.id()).stream()
            .map(RecoveryService::toTaskView)
            .toList();
        List<AppealDraftView> appeals = store.appealDrafts(clinicId, c.id()).stream()
            .map(RecoveryService::toAppealDraftView)
            .toList();
        List<AppealSubmissionView> submissions = store.appealSubmissions(clinicId, c.id()).stream()
            .map(RecoveryService::toAppealSubmissionView)
            .toList();
        return new RecoveryCaseDetail(summary, toDenialView(denial), ledger, tasks, appeals, submissions);
    }

    private static RecoveryCaseSummary toCaseSummary(StoredRecoveryCase c,
                                                     StoredDenialEvent denial,
                                                     ClaimPaymentStatus paymentStatus,
                                                     String currency,
                                                     int currencyScale) {
        return new RecoveryCaseSummary(
            c.id(),
            c.clinicId(),
            c.claimId(),
            c.revisionId(),
            c.payerId(),
            c.primaryDenialEventId(),
            c.caseStatus(),
            c.recoveryOutcome(),
            paymentStatus,
            c.assignedToUserId(),
            c.dueDate(),
            c.dueDateBasis(),
            c.dueDateRuleVersionId(),
            c.dueDateSourceNote(),
            RecoveryDomain.toMoney(denial.deniedAmount(), currency, currencyScale),
            RecoveryDomain.toMoney(c.estimatedRecoverableAmount(), currency, currencyScale),
            c.estimateBasis(),
            c.estimateNote(),
            c.closureReason(),
            c.version(),
            c.createdBy(),
            c.createdAt(),
            c.updatedAt()
        );
    }

    private static LedgerEntryView toLedgerView(StoredLedgerEntry e, boolean reversed) {
        return new LedgerEntryView(
            e.id(),
            e.clinicId(),
            e.claimId(),
            e.revisionId(),
            e.entryType(),
            RecoveryDomain.toMoney(e.amount(), e.currency(), e.currencyScale()),
            RecoveryDomain.toMoney(e.previousOutstandingAmount(), e.currency(), e.currencyScale()),
            RecoveryDomain.toMoney(e.resultingOutstandingAmount(), e.currency(), e.currencyScale()),
            e.occurredOn(),
            e.payerReference(),
            e.sourceDocumentId(),
            e.reversesEntryId(),
            reversed,
            e.reason(),
            e.recordedBy(),
            e.recordedAt()
        );
    }

    private static DenialEventView toDenialView(StoredDenialEvent d) {
        return new DenialEventView(
            d.id(),
            d.clinicId(),
            d.claimId(),
            d.revisionId(),
            d.payerId(),
            d.ledgerEntryId(),
            d.denialKind(),
            d.sourceReasonCode(),
            d.originalReasonText(),
            d.normalizedCategoryKey(),
            d.categoryMappingSource(),
            d.mappingRuleVersionId(),
            RecoveryDomain.toMoney(d.deniedAmount(), d.currency(), d.currencyScale()),
            d.noticeDate(),
            d.receivedDate(),
            d.payerReference(),
            d.sourceDocumentId(),
            d.recordedBy(),
            d.recordedAt()
        );
    }

    private static RecoveryTaskView toTaskView(StoredRecoveryTask t) {
        return new RecoveryTaskView(
            t.id(),
            t.clinicId(),
            t.recoveryCaseId(),
            t.taskKind(),
            t.title(),
            t.description(),
            t.requiredDocumentKey(),
            t.satisfiedDocumentId(),
            t.status(),
            t.assignedToUserId(),
            t.dueDate(),
            t.resolutionNote(),
            t.completedBy(),
            t.completedAt(),
            t.version(),
            t.createdBy(),
            t.createdAt(),
            t.updatedAt()
        );
    }

    private static AppealDraftView toAppealDraftView(StoredAppealDraft a) {
        return new AppealDraftView(
            a.id(),
            a.clinicId(),
            a.recoveryCaseId(),
            a.versionNumber(),
            a.status(),
            a.locale(),
            a.subjectLine(),
            a.bodyText(),
            a.factualBasisSummary(),
            a.citedEvidenceIds(),
            a.unresolvedPlaceholderCount(),
            a.approvedBy(),
            a.approvedAt(),
            a.approvalNote(),
            a.rejectedBy(),
            a.rejectedAt(),
            a.rejectionReason(),
            a.createdBy(),
            a.createdAt()
        );
    }

    private static AppealSubmissionView toAppealSubmissionView(StoredAppealSubmission s) {
        return new AppealSubmissionView(
            s.id(),
            s.clinicId(),
            s.recoveryCaseId(),
            s.appealDraftVersionId(),
            s.channelCode(),
            s.externalSubmissionRef(),
            s.occurredOn(),
            s.occurredAt(),
            s.occurrencePrecision(),
            s.evidenceDocumentId(),
            s.notes(),
            s.recordedBy(),
            s.recordedAt()
        );
    }

    private static String computeLedgerRequestHash(RecordLedgerEntryCommand cmd) {
        return RecoveryDomain.sha256Hex(String.join("|",
            String.valueOf(cmd.entryType()),
            cmd.amount() == null ? "" : cmd.amount().strip(),
            String.valueOf(cmd.occurredOn()),
            cmd.payerReference() == null ? "" : cmd.payerReference().strip(),
            String.valueOf(cmd.sourceDocumentId()),
            String.valueOf(cmd.reversesEntryId()),
            cmd.reason() == null ? "" : cmd.reason().strip()
        ));
    }

    private static String computeDenialRequestHash(RecordDenialCommand cmd,
                                                   String originalReason,
                                                   String sourceReasonCode,
                                                   String payerReference) {
        return RecoveryDomain.sha256Hex(String.join("|",
            String.valueOf(cmd.denialKind()),
            sourceReasonCode == null ? "" : sourceReasonCode,
            originalReason,
            cmd.normalizedCategoryKey() == null ? "" : cmd.normalizedCategoryKey().strip(),
            cmd.deniedAmount() == null ? "" : cmd.deniedAmount().strip(),
            String.valueOf(cmd.noticeDate()),
            String.valueOf(cmd.receivedDate()),
            payerReference == null ? "" : payerReference,
            String.valueOf(cmd.sourceDocumentId()),
            String.valueOf(cmd.staffConfirmedDueDate()),
            cmd.dueDateSourceNote() == null ? "" : cmd.dueDateSourceNote().strip()
        ));
    }

    private static String computeAppealSubmissionHash(RecordAppealSubmissionCommand cmd,
                                                      String channelCode,
                                                      String externalRef,
                                                      String notes) {
        return RecoveryDomain.sha256Hex(String.join("|",
            String.valueOf(cmd.appealDraftVersionId()),
            channelCode,
            externalRef,
            String.valueOf(cmd.occurredOn()),
            String.valueOf(cmd.occurredAt()),
            String.valueOf(cmd.occurrencePrecision()),
            String.valueOf(cmd.evidenceDocumentId()),
            notes == null ? "" : notes
        ));
    }

    private static void validatePage(int page) {
        if (page < 0 || page > 10000) throw new Failure(400, "INVALID_PAGE");
    }
}
