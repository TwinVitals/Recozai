package sn.recozai.recovery.adapter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sn.recozai.access.Actor;
import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.recovery.application.RecoveryService;
import sn.recozai.recovery.application.RecoveryService.*;
import sn.recozai.shared.Page;
import sn.recozai.web.Versions;
import java.net.URI;
import java.time.*;
import java.util.*;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/v1")
public class RecoveryController {
    private final RecoveryService service;

    public RecoveryController(RecoveryService service) {
        this.service = service;
    }

    @GetMapping("/claims/{claimId}/ledger")
    public ClaimLedgerSummary claimLedger(@AuthenticationPrincipal Actor actor,
                                          @PathVariable UUID claimId) {
        return service.claimLedger(actor, claimId);
    }

    @PostMapping("/claims/{claimId}/ledger-entries")
    public ResponseEntity<LedgerEntryView> recordLedgerEntry(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID claimId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
        @Valid @RequestBody LedgerEntryBody body
    ) {
        String resolvedKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader
            : body.idempotencyKey();
        LedgerEntryView entry = service.recordLedgerEntry(
            actor,
            claimId,
            new RecordLedgerEntryCommand(
                body.entryType(),
                body.amount(),
                body.occurredOn(),
                body.payerReference(),
                body.sourceDocumentId(),
                body.reversesEntryId(),
                body.reason(),
                resolvedKey
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @PostMapping("/claims/{claimId}/denial-events")
    public ResponseEntity<RecoveryCaseDetail> recordDenialEvent(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID claimId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
        @Valid @RequestBody DenialEventBody body
    ) {
        String resolvedKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader
            : body.idempotencyKey();
        RecoveryCaseDetail created = service.recordDenialEvent(
            actor,
            claimId,
            new RecordDenialCommand(
                body.denialKind(),
                body.sourceReasonCode(),
                body.originalReasonText(),
                body.normalizedCategoryKey(),
                body.deniedAmount(),
                body.noticeDate(),
                body.receivedDate(),
                body.payerReference(),
                body.sourceDocumentId(),
                body.staffConfirmedDueDate(),
                body.dueDateSourceNote(),
                resolvedKey
            )
        );
        return ResponseEntity.created(URI.create("/api/v1/recovery-cases/" + created.summary().id()))
            .eTag(Long.toString(created.summary().version()))
            .body(created);
    }

    @GetMapping("/recovery-cases")
    public Page<RecoveryCaseSummary> listRecoveryCases(@AuthenticationPrincipal Actor actor,
                                                       @RequestParam(required = false) UUID payerId,
                                                       @RequestParam(required = false) DenialCaseStatus status,
                                                       @RequestParam(defaultValue = "0") int page) {
        return service.recoveryCases(actor, payerId, status, page);
    }

    @GetMapping("/recovery-cases/{caseId}")
    public ResponseEntity<RecoveryCaseDetail> getRecoveryCase(@AuthenticationPrincipal Actor actor,
                                                              @PathVariable UUID caseId) {
        RecoveryCaseDetail detail = service.recoveryCase(actor, caseId);
        return ResponseEntity.ok()
            .eTag(Long.toString(detail.summary().version()))
            .body(detail);
    }

    @PatchMapping("/recovery-cases/{caseId}")
    public ResponseEntity<RecoveryCaseDetail> updateRecoveryCase(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID caseId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @Valid @RequestBody UpdateRecoveryCaseBody body
    ) {
        RecoveryCaseDetail updated = service.updateRecoveryCase(
            actor,
            caseId,
            new UpdateRecoveryCaseCommand(
                body.caseStatus(),
                body.recoveryOutcome(),
                body.assignedToUserId(),
                body.staffConfirmedDueDate(),
                body.dueDateSourceNote(),
                body.estimatedRecoverableAmount(),
                body.estimateBasis(),
                body.estimateNote(),
                body.closureReason()
            ),
            Versions.parse(ifMatch)
        );
        return ResponseEntity.ok()
            .eTag(Long.toString(updated.summary().version()))
            .body(updated);
    }

    @PostMapping("/recovery-cases/{caseId}/tasks")
    public ResponseEntity<RecoveryTaskView> createTask(@AuthenticationPrincipal Actor actor,
                                                       @PathVariable UUID caseId,
                                                       @Valid @RequestBody CreateTaskBody body) {
        RecoveryTaskView created = service.createTask(
            actor,
            caseId,
            new CreateTaskCommand(
                body.taskKind(),
                body.title(),
                body.description(),
                body.requiredDocumentKey(),
                body.assignedToUserId(),
                body.dueDate()
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Long.toString(created.version()))
            .body(created);
    }

    @PatchMapping("/recovery-cases/{caseId}/tasks/{taskId}")
    public ResponseEntity<RecoveryTaskView> updateTask(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID caseId,
        @PathVariable UUID taskId,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @Valid @RequestBody UpdateTaskBody body
    ) {
        RecoveryTaskView updated = service.updateTask(
            actor,
            caseId,
            taskId,
            new UpdateTaskCommand(
                body.status(),
                body.assignedToUserId(),
                body.dueDate(),
                body.satisfiedDocumentId(),
                body.resolutionNote()
            ),
            Versions.parse(ifMatch)
        );
        return ResponseEntity.ok()
            .eTag(Long.toString(updated.version()))
            .body(updated);
    }

    @PostMapping("/recovery-cases/{caseId}/appeal-drafts")
    public ResponseEntity<AppealDraftView> createAppealDraft(@AuthenticationPrincipal Actor actor,
                                                             @PathVariable UUID caseId,
                                                             @Valid @RequestBody CreateAppealDraftBody body) {
        AppealDraftView created = service.createAppealDraft(
            actor,
            caseId,
            new CreateAppealDraftCommand(
                body.locale(),
                body.subjectLine(),
                body.bodyText(),
                body.factualBasisSummary(),
                body.citedEvidenceIds(),
                Boolean.TRUE.equals(body.submitForApproval())
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/recovery-cases/{caseId}/appeal-drafts/{draftId}/review")
    public AppealDraftView reviewAppealDraft(@AuthenticationPrincipal Actor actor,
                                             @PathVariable UUID caseId,
                                             @PathVariable UUID draftId,
                                             @Valid @RequestBody ReviewAppealBody body) {
        return service.reviewAppealDraft(
            actor,
            caseId,
            draftId,
            new ReviewAppealCommand(body.approved(), body.note())
        );
    }

    @PostMapping("/recovery-cases/{caseId}/appeal-submissions")
    public ResponseEntity<AppealSubmissionView> recordAppealSubmission(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID caseId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
        @Valid @RequestBody AppealSubmissionBody body
    ) {
        String resolvedKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader
            : body.idempotencyKey();
        AppealSubmissionView submission = service.recordAppealSubmission(
            actor,
            caseId,
            new RecordAppealSubmissionCommand(
                body.appealDraftVersionId(),
                body.channelCode(),
                body.externalSubmissionRef(),
                body.occurredOn(),
                body.occurredAt(),
                body.occurrencePrecision(),
                body.evidenceDocumentId(),
                body.notes(),
                resolvedKey
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(submission);
    }

    public record LedgerEntryBody(
        @NotNull LedgerEntryType entryType,
        @NotBlank @Size(max = 40) String amount,
        @NotNull LocalDate occurredOn,
        @Size(max = 120) String payerReference,
        UUID sourceDocumentId,
        UUID reversesEntryId,
        @NotBlank @Size(max = 240) String reason,
        @Size(max = 120) String idempotencyKey
    ) {}

    public record DenialEventBody(
        @NotNull DenialKind denialKind,
        @Size(max = 80) String sourceReasonCode,
        @NotBlank @Size(max = 2000) String originalReasonText,
        @Size(max = 80) String normalizedCategoryKey,
        @NotBlank @Size(max = 40) String deniedAmount,
        @NotNull LocalDate noticeDate,
        @NotNull LocalDate receivedDate,
        @Size(max = 120) String payerReference,
        UUID sourceDocumentId,
        LocalDate staffConfirmedDueDate,
        @Size(max = 240) String dueDateSourceNote,
        @Size(max = 120) String idempotencyKey
    ) {}

    public record UpdateRecoveryCaseBody(
        DenialCaseStatus caseStatus,
        RecoveryOutcome recoveryOutcome,
        UUID assignedToUserId,
        LocalDate staffConfirmedDueDate,
        @Size(max = 240) String dueDateSourceNote,
        @Size(max = 40) String estimatedRecoverableAmount,
        EstimateBasis estimateBasis,
        @Size(max = 240) String estimateNote,
        @Size(max = 240) String closureReason
    ) {}

    public record CreateTaskBody(
        @NotNull RecoveryTaskKind taskKind,
        @NotBlank @Size(max = 160) String title,
        @Size(max = 500) String description,
        @Size(max = 80) String requiredDocumentKey,
        UUID assignedToUserId,
        LocalDate dueDate
    ) {}

    public record UpdateTaskBody(
        @NotNull RecoveryTaskStatus status,
        UUID assignedToUserId,
        LocalDate dueDate,
        UUID satisfiedDocumentId,
        @Size(max = 240) String resolutionNote
    ) {}

    public record CreateAppealDraftBody(
        @Size(max = 16) String locale,
        @NotBlank @Size(max = 200) String subjectLine,
        @NotBlank @Size(max = 8000) String bodyText,
        @NotBlank @Size(max = 1000) String factualBasisSummary,
        List<UUID> citedEvidenceIds,
        Boolean submitForApproval
    ) {}

    public record ReviewAppealBody(
        @NotNull Boolean approved,
        @Size(max = 240) String note
    ) {}

    public record AppealSubmissionBody(
        @NotNull UUID appealDraftVersionId,
        @NotBlank @Size(max = 60) String channelCode,
        @NotBlank @Size(max = 120) String externalSubmissionRef,
        @NotNull LocalDate occurredOn,
        Instant occurredAt,
        @NotNull OccurrencePrecision occurrencePrecision,
        UUID evidenceDocumentId,
        @Size(max = 240) String notes,
        @Size(max = 120) String idempotencyKey
    ) {}
}
