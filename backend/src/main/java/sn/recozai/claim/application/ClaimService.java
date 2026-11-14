package sn.recozai.claim.application;

import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.claim.application.ClaimStore.*;
import sn.recozai.claim.domain.ClaimDomain;
import sn.recozai.claim.domain.ClaimDomain.*;
import sn.recozai.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ClaimService {
    @FunctionalInterface
    public interface ClinicPolicyReader {
        CurrencyPolicy currentPolicy(UUID clinicId);
    }

    @FunctionalInterface
    public interface PayerValidator {
        boolean isActivePayer(UUID clinicId, UUID payerId);
    }

    @FunctionalInterface
    public interface ClaimDocumentValidator {
        boolean isCleanClaimDocument(UUID clinicId, UUID claimId, UUID documentId);
    }

    public record CurrencyPolicy(String currency, int fractionDigits) {}

    public record SubmissionCommand(UUID revisionId, SubmissionEventKind eventKind,
                                    String channelCode, String externalSubmissionRef,
                                    LocalDate occurredOn, Instant occurredAt,
                                    OccurrencePrecision occurrencePrecision,
                                    UUID evidenceDocumentId, UUID resubmissionOfId,
                                    UUID supersedesId, String correctionReason,
                                    String idempotencyKey) {}

    private final ClaimStore store;
    private final ClinicPolicyReader clinicPolicies;
    private final PayerValidator payers;
    private final ClaimDocumentValidator documents;
    private final Transactions transactions;
    private final Access access;
    private final Audit audit;
    private final Clock clock;

    public ClaimService(ClaimStore store, ClinicPolicyReader clinicPolicies, PayerValidator payers,
                        Transactions transactions, Access access, Audit audit, Clock clock) {
        this(store, clinicPolicies, payers, (c, cl, d) -> false, transactions, access, audit, clock);
    }

    public ClaimService(ClaimStore store, ClinicPolicyReader clinicPolicies, PayerValidator payers,
                        ClaimDocumentValidator documents, Transactions transactions,
                        Access access, Audit audit, Clock clock) {
        this.store = store;
        this.clinicPolicies = clinicPolicies;
        this.payers = payers;
        this.documents = documents;
        this.transactions = transactions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public record ActiveClaimContext(UUID claimId, UUID clinicId, UUID payerId,
                                     String externalReference, String currency,
                                     int currencyScale, ClaimState state,
                                     UUID activeRevisionId, int activeRevisionNumber,
                                     BigDecimal submittedAmount) {}

    public boolean claimExists(UUID clinicId, UUID claimId) {
        return store.claim(clinicId, claimId).isPresent();
    }

    public Optional<ActiveClaimContext> lockActiveClaimContext(UUID clinicId, UUID claimId) {
        return store.lockClaim(clinicId, claimId).flatMap(c -> resolveActiveContext(clinicId, c));
    }

    public Optional<ActiveClaimContext> activeClaimContext(UUID clinicId, UUID claimId) {
        return store.claim(clinicId, claimId).flatMap(c -> resolveActiveContext(clinicId, c));
    }

    private Optional<ActiveClaimContext> resolveActiveContext(UUID clinicId, StoredClaim c) {
        if (c.activeRevisionId() == null) return Optional.empty();
        return store.revision(clinicId, c.id(), c.activeRevisionId())
            .map(rev -> new ActiveClaimContext(
                c.id(), c.clinicId(), c.payerId(), c.externalReference(),
                c.currency(), c.currencyScale(), c.state(),
                rev.id(), rev.revisionNumber(), rev.submittedAmount()
            ));
    }

    public Page<ClaimSummary> claims(Actor actor, UUID payerId, ClaimState state, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimRead();
            var result = store.claims(current.clinicId(), payerId, state, page);
            List<ClaimSummary> summaries = result.items().stream()
                .map(c -> toSummary(c, store.revisions(current.clinicId(), c.id()),
                    store.submissionEvents(current.clinicId(), c.id())))
                .toList();
            return new Page<>(summaries, page, 25, result.totalElements());
        });
    }

    public ClaimDetail claim(Actor actor, UUID id) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimRead();
            StoredClaim stored = store.claim(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            return buildDetail(stored);
        });
    }

    public ClaimDetail createDraft(Actor actor, UUID payerId, String externalReference,
                                   String pseudonymousPatientRef, LocalDate sourceDate,
                                   List<ClaimLineInput> lines) {
        if (payerId == null) throw new Failure(422, "INVALID_CLAIM");
        String validatedExtRef = ClaimDomain.validateExternalReference(externalReference);
        String validatedPatientRef = ClaimDomain.validatePatientRef(pseudonymousPatientRef);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimWrite();
            if (!payers.isActivePayer(current.clinicId(), payerId)) {
                throw new Failure(422, "PAYER_NOT_ACTIVE");
            }
            if (store.externalReferenceExists(current.clinicId(), payerId, validatedExtRef, null)) {
                throw new Failure(409, "CLAIM_REFERENCE_ALREADY_EXISTS");
            }
            CurrencyPolicy policy = clinicPolicies.currentPolicy(current.clinicId());
            List<ValidatedLine> validatedLines = ClaimDomain.validateLines(lines, policy.fractionDigits(), true);
            Instant now = clock.instant();
            StoredClaim draft = new StoredClaim(
                UUID.randomUUID(),
                current.clinicId(),
                payerId,
                validatedExtRef,
                validatedPatientRef,
                policy.currency(),
                policy.fractionDigits(),
                ClaimState.DRAFT,
                sourceDate,
                null,
                null,
                0,
                current.userId(),
                now,
                now
            );
            store.createDraftClaim(draft, validatedLines);
            audit.record(current.userId(), current.clinicId(), "CLAIM_DRAFT_CREATED", draft.id());
            return buildDetail(draft);
        });
    }

    public ClaimDetail updateDraft(Actor actor, UUID id, UUID payerId, String externalReference,
                                   String pseudonymousPatientRef, LocalDate sourceDate,
                                   List<ClaimLineInput> lines, long expectedVersion) {
        if (payerId == null) throw new Failure(422, "INVALID_CLAIM");
        String validatedExtRef = ClaimDomain.validateExternalReference(externalReference);
        String validatedPatientRef = ClaimDomain.validatePatientRef(pseudonymousPatientRef);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimWrite();
            StoredClaim existing = store.lockClaim(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (existing.state() != ClaimState.DRAFT) {
                throw new Failure(409, "CLAIM_NOT_EDITABLE_AS_DRAFT");
            }
            if (!payers.isActivePayer(current.clinicId(), payerId)) {
                throw new Failure(422, "PAYER_NOT_ACTIVE");
            }
            if (store.externalReferenceExists(current.clinicId(), payerId, validatedExtRef, id)) {
                throw new Failure(409, "CLAIM_REFERENCE_ALREADY_EXISTS");
            }
            List<ValidatedLine> validatedLines = ClaimDomain.validateLines(lines, existing.currencyScale(), true);
            Instant now = clock.instant();
            StoredClaim updated = new StoredClaim(
                existing.id(),
                existing.clinicId(),
                payerId,
                validatedExtRef,
                validatedPatientRef,
                existing.currency(),
                existing.currencyScale(),
                ClaimState.DRAFT,
                sourceDate,
                null,
                null,
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.updateDraftClaim(updated, validatedLines, expectedVersion);
            audit.record(current.userId(), current.clinicId(), "CLAIM_DRAFT_UPDATED", id);
            return buildDetail(updated);
        });
    }

    public ClaimDetail registerClaim(Actor actor, UUID id, UUID evidenceDocumentId, long expectedVersion) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimWrite();
            StoredClaim existing = store.lockClaim(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (existing.state() != ClaimState.DRAFT) {
                throw new Failure(409, "CLAIM_ALREADY_REGISTERED");
            }
            if (evidenceDocumentId != null
                && !documents.isCleanClaimDocument(current.clinicId(), id, evidenceDocumentId)) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }
            List<ValidatedLine> draftLines = store.draftLines(current.clinicId(), id);
            ValidatedRevision prepared = ClaimDomain.prepareRevision(
                existing.payerId(),
                existing.externalReference(),
                existing.pseudonymousPatientRef(),
                existing.currency(),
                existing.currencyScale(),
                1,
                existing.draftSourceDate(),
                null,
                draftLines
            );
            Instant now = clock.instant();
            UUID revisionId = UUID.randomUUID();
            StoredRevision firstRevision = new StoredRevision(
                revisionId,
                current.clinicId(),
                id,
                1,
                prepared.totalAmount(),
                prepared.sourceDate(),
                prepared.contentHash(),
                null,
                evidenceDocumentId,
                prepared.lines(),
                current.userId(),
                now
            );
            StoredClaim updated = new StoredClaim(
                existing.id(),
                existing.clinicId(),
                existing.payerId(),
                existing.externalReference(),
                existing.pseudonymousPatientRef(),
                existing.currency(),
                existing.currencyScale(),
                ClaimState.REGISTERED,
                existing.draftSourceDate(),
                revisionId,
                null,
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.registerFirstRevision(updated, firstRevision, expectedVersion);
            audit.record(current.userId(), current.clinicId(), "CLAIM_REGISTERED", id);
            return buildDetail(updated);
        });
    }

    public ClaimDetail amendRegisteredClaim(Actor actor, UUID id, LocalDate sourceDate,
                                            String changeReason, List<ClaimLineInput> lines,
                                            UUID evidenceDocumentId, long expectedVersion) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimManage();
            StoredClaim existing = store.lockClaim(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (existing.state() != ClaimState.REGISTERED && existing.state() != ClaimState.SUBMITTED) {
                throw new Failure(409, "CLAIM_AMENDMENT_NOT_ALLOWED");
            }
            if (evidenceDocumentId != null
                && !documents.isCleanClaimDocument(current.clinicId(), id, evidenceDocumentId)) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }
            List<ValidatedLine> validatedLines = ClaimDomain.validateLines(lines, existing.currencyScale(), false);
            int nextRevNum = store.nextRevisionNumber(current.clinicId(), id);
            ValidatedRevision prepared = ClaimDomain.prepareRevision(
                existing.payerId(),
                existing.externalReference(),
                existing.pseudonymousPatientRef(),
                existing.currency(),
                existing.currencyScale(),
                nextRevNum,
                sourceDate,
                changeReason,
                validatedLines
            );
            Instant now = clock.instant();
            UUID revisionId = UUID.randomUUID();
            StoredRevision amendedRevision = new StoredRevision(
                revisionId,
                current.clinicId(),
                id,
                nextRevNum,
                prepared.totalAmount(),
                prepared.sourceDate(),
                prepared.contentHash(),
                prepared.changeReason(),
                evidenceDocumentId,
                prepared.lines(),
                current.userId(),
                now
            );
            StoredClaim updated = new StoredClaim(
                existing.id(),
                existing.clinicId(),
                existing.payerId(),
                existing.externalReference(),
                existing.pseudonymousPatientRef(),
                existing.currency(),
                existing.currencyScale(),
                existing.state(),
                existing.draftSourceDate(),
                revisionId,
                existing.stateReason(),
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.appendAmendedRevision(updated, amendedRevision, expectedVersion);
            audit.record(current.userId(), current.clinicId(), "CLAIM_AMENDED", id);
            return buildDetail(updated);
        });
    }

    public ClaimDetail transitionState(Actor actor, UUID id, ClaimTransitionAction action,
                                       String reason, long expectedVersion) {
        if (action == null) throw new Failure(422, "INVALID_STATE_TRANSITION");
        String validatedReason = ClaimDomain.validateReason(reason, "TRANSITION_REASON_REQUIRED");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimManage();
            StoredClaim existing = store.lockClaim(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            List<StoredSubmissionEvent> events = store.submissionEvents(current.clinicId(), id);
            ClaimState targetState;
            String auditAction;
            switch (action) {
                case VOID -> {
                    if (existing.state() != ClaimState.REGISTERED || !events.isEmpty()) {
                        throw new Failure(409, "CLAIM_VOID_NOT_ALLOWED");
                    }
                    targetState = ClaimState.VOIDED;
                    auditAction = "CLAIM_VOIDED";
                }
                case ARCHIVE -> {
                    if (existing.state() != ClaimState.SUBMITTED) {
                        throw new Failure(409, "CLAIM_ARCHIVE_NOT_ALLOWED");
                    }
                    targetState = ClaimState.ARCHIVED;
                    auditAction = "CLAIM_ARCHIVED";
                }
                case REOPEN -> {
                    if (existing.state() != ClaimState.ARCHIVED) {
                        throw new Failure(409, "CLAIM_REOPEN_NOT_ALLOWED");
                    }
                    targetState = ClaimState.SUBMITTED;
                    auditAction = "CLAIM_REOPENED";
                }
                default -> throw new Failure(422, "INVALID_STATE_TRANSITION");
            }
            Instant now = clock.instant();
            StoredClaim updated = new StoredClaim(
                existing.id(),
                existing.clinicId(),
                existing.payerId(),
                existing.externalReference(),
                existing.pseudonymousPatientRef(),
                existing.currency(),
                existing.currencyScale(),
                targetState,
                existing.draftSourceDate(),
                existing.activeRevisionId(),
                validatedReason,
                expectedVersion + 1,
                existing.createdBy(),
                existing.createdAt(),
                now
            );
            store.transitionClaimState(updated, expectedVersion);
            audit.record(current.userId(), current.clinicId(), auditAction, id);
            return buildDetail(updated);
        });
    }

    public List<ClaimSubmissionEvent> submissionRecords(Actor actor, UUID claimId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requireClaimRead();
            store.claim(current.clinicId(), claimId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            return mapSubmissionEvents(store.submissionEvents(current.clinicId(), claimId));
        });
    }

    public ClaimSubmissionEvent recordSubmission(Actor actor, UUID claimId, SubmissionCommand cmd) {
        if (cmd == null || cmd.revisionId() == null || cmd.eventKind() == null
            || cmd.occurredOn() == null || cmd.occurrencePrecision() == null) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        String idempotencyKey = ClaimDomain.validateIdempotencyKey(cmd.idempotencyKey());
        String channelCode = ClaimDomain.normalizeChannelCode(cmd.channelCode());
        String externalRef = ClaimDomain.validateExternalSubmissionRef(cmd.externalSubmissionRef());
        if (cmd.occurrencePrecision() == OccurrencePrecision.INSTANT && cmd.occurredAt() == null) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        if (cmd.occurrencePrecision() == OccurrencePrecision.DATE && cmd.occurredAt() != null) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        String cleanedReason = null;
        if (cmd.eventKind() == SubmissionEventKind.RESUBMISSION
            || cmd.eventKind() == SubmissionEventKind.METADATA_CORRECTION) {
            cleanedReason = ClaimDomain.validateReason(cmd.correctionReason(), "CORRECTION_REASON_REQUIRED");
        } else if (cmd.correctionReason() != null && !cmd.correctionReason().isBlank()) {
            cleanedReason = ClaimDomain.validateReason(cmd.correctionReason(), "INVALID_SUBMISSION_EVENT");
        }
        String requestHash = ClaimDomain.computeSubmissionRequestHash(
            cmd.revisionId(), cmd.eventKind(), channelCode, externalRef,
            cmd.occurredOn(), cmd.occurredAt(), cmd.occurrencePrecision(),
            cmd.evidenceDocumentId(), cmd.resubmissionOfId(), cmd.supersedesId(), cleanedReason
        );
        final String finalReason = cleanedReason;

        return transactions.execute(() -> {
            Actor current = access.current(actor);
            if (cmd.eventKind() == SubmissionEventKind.METADATA_CORRECTION) {
                current.requireClaimManage();
            } else {
                current.requireClaimWrite();
            }
            StoredClaim claim = store.lockClaim(current.clinicId(), claimId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));

            Optional<StoredSubmissionEvent> existingByKey =
                store.submissionEventByIdempotencyKey(current.clinicId(), claimId, idempotencyKey);
            if (existingByKey.isPresent()) {
                StoredSubmissionEvent replay = existingByKey.get();
                if (!replay.requestHash().equals(requestHash)) {
                    throw new Failure(409, "IDEMPOTENCY_KEY_CONFLICT");
                }
                List<StoredSubmissionEvent> allEvents = store.submissionEvents(current.clinicId(), claimId);
                return mapSingleSubmissionEvent(replay, allEvents);
            }

            if (claim.state() != ClaimState.REGISTERED && claim.state() != ClaimState.SUBMITTED) {
                throw new Failure(409, "CLAIM_SUBMISSION_NOT_ALLOWED");
            }
            if (cmd.evidenceDocumentId() != null
                && !documents.isCleanClaimDocument(current.clinicId(), claimId, cmd.evidenceDocumentId())) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }
            StoredRevision targetRevision = store.revision(current.clinicId(), claimId, cmd.revisionId())
                .orElseThrow(() -> new Failure(422, "INVALID_SUBMISSION_REVISION"));

            List<StoredSubmissionEvent> existingEvents = store.submissionEvents(current.clinicId(), claimId);
            validateSubmissionChain(cmd, targetRevision, existingEvents);

            Instant now = clock.instant();
            StoredSubmissionEvent created = new StoredSubmissionEvent(
                UUID.randomUUID(),
                current.clinicId(),
                claimId,
                targetRevision.id(),
                targetRevision.revisionNumber(),
                cmd.eventKind(),
                channelCode,
                externalRef,
                cmd.occurredOn(),
                cmd.occurredAt(),
                cmd.occurrencePrecision(),
                cmd.evidenceDocumentId(),
                cmd.resubmissionOfId(),
                cmd.supersedesId(),
                finalReason,
                idempotencyKey,
                requestHash,
                current.userId(),
                now
            );
            ClaimState nextState = claim.state() == ClaimState.REGISTERED ? ClaimState.SUBMITTED : claim.state();
            store.appendSubmissionEvent(created, nextState);
            String auditAction = switch (cmd.eventKind()) {
                case SUBMISSION -> "CLAIM_SUBMISSION_RECORDED";
                case RESUBMISSION -> "CLAIM_RESUBMISSION_RECORDED";
                case METADATA_CORRECTION -> "CLAIM_SUBMISSION_CORRECTED";
            };
            audit.record(current.userId(), current.clinicId(), auditAction, created.id());
            List<StoredSubmissionEvent> updatedEvents = new ArrayList<>(existingEvents.size() + 1);
            updatedEvents.addAll(existingEvents);
            updatedEvents.add(created);
            return mapSingleSubmissionEvent(created, updatedEvents);
        });
    }

    private static void validateSubmissionChain(SubmissionCommand cmd,
                                                StoredRevision targetRevision,
                                                List<StoredSubmissionEvent> existingEvents) {
        Set<UUID> supersededIds = new HashSet<>();
        Map<UUID, StoredSubmissionEvent> byId = new HashMap<>();
        boolean revisionHasSubmission = false;
        for (StoredSubmissionEvent event : existingEvents) {
            byId.put(event.id(), event);
            if (event.supersedesId() != null) supersededIds.add(event.supersedesId());
            if (event.revisionId().equals(targetRevision.id())) revisionHasSubmission = true;
        }
        switch (cmd.eventKind()) {
            case SUBMISSION -> {
                if (cmd.resubmissionOfId() != null || cmd.supersedesId() != null) {
                    throw new Failure(422, "INVALID_SUBMISSION_EVENT");
                }
                if (revisionHasSubmission) {
                    throw new Failure(409, "REVISION_ALREADY_SUBMITTED");
                }
            }
            case RESUBMISSION -> {
                if (cmd.resubmissionOfId() == null || cmd.supersedesId() != null) {
                    throw new Failure(422, "INVALID_SUBMISSION_EVENT");
                }
                StoredSubmissionEvent prior = byId.get(cmd.resubmissionOfId());
                if (prior == null) {
                    throw new Failure(422, "INVALID_RESUBMISSION_TARGET");
                }
                if (supersededIds.contains(prior.id())) {
                    throw new Failure(409, "SUBMISSION_EVENT_SUPERSEDED");
                }
            }
            case METADATA_CORRECTION -> {
                if (cmd.supersedesId() == null || cmd.resubmissionOfId() != null) {
                    throw new Failure(422, "INVALID_SUBMISSION_EVENT");
                }
                StoredSubmissionEvent prior = byId.get(cmd.supersedesId());
                if (prior == null || !prior.revisionId().equals(targetRevision.id())) {
                    throw new Failure(422, "INVALID_CORRECTION_TARGET");
                }
                if (supersededIds.contains(prior.id())) {
                    throw new Failure(409, "SUBMISSION_EVENT_SUPERSEDED");
                }
            }
        }
    }

    private ClaimDetail buildDetail(StoredClaim c) {
        List<ValidatedLine> draftLines = c.state() == ClaimState.DRAFT
            ? store.draftLines(c.clinicId(), c.id())
            : List.of();
        List<StoredRevision> storedRevisions = store.revisions(c.clinicId(), c.id());
        List<StoredSubmissionEvent> events = store.submissionEvents(c.clinicId(), c.id());

        List<ClaimLine> mappedDraftLines = draftLines.stream()
            .map(l -> new ClaimLine(
                l.id(),
                l.lineNumber(),
                l.serviceCode(),
                l.description(),
                ClaimDomain.toMoney(l.submittedAmount(), c.currency(), c.currencyScale())
            ))
            .toList();

        List<ClaimRevision> mappedRevisions = storedRevisions.stream()
            .map(r -> {
                DerivedSubmissionSummary sub = ClaimDomain.deriveRevisionSubmissionSummary(r.id(), events);
                List<ClaimLine> revLines = r.lines().stream()
                    .map(l -> new ClaimLine(
                        l.id(),
                        l.lineNumber(),
                        l.serviceCode(),
                        l.description(),
                        ClaimDomain.toMoney(l.submittedAmount(), c.currency(), c.currencyScale())
                    ))
                    .toList();
                return new ClaimRevision(
                    r.id(),
                    r.revisionNumber(),
                    ClaimDomain.toMoney(r.submittedAmount(), c.currency(), c.currencyScale()),
                    r.sourceDate(),
                    r.contentHash(),
                    r.changeReason(),
                    r.evidenceDocumentId(),
                    sub.state(),
                    sub.externalSubmissionCount(),
                    sub.effectiveChannelCode(),
                    sub.effectiveExternalSubmissionRef(),
                    sub.effectiveOccurredOn(),
                    sub.effectiveOccurredAt(),
                    sub.effectiveOccurrencePrecision(),
                    revLines,
                    r.registeredBy(),
                    r.registeredAt()
                );
            })
            .toList();

        boolean activeSubmitted = false;
        if (c.activeRevisionId() != null) {
            DerivedSubmissionSummary activeSub =
                ClaimDomain.deriveRevisionSubmissionSummary(c.activeRevisionId(), events);
            activeSubmitted = activeSub.state() == RevisionSubmissionState.SUBMITTED;
        }

        return new ClaimDetail(
            c.id(),
            c.clinicId(),
            c.payerId(),
            c.externalReference(),
            c.pseudonymousPatientRef(),
            c.currency(),
            c.currencyScale(),
            c.state(),
            c.draftSourceDate(),
            c.activeRevisionId(),
            activeSubmitted,
            c.stateReason(),
            mappedDraftLines,
            mappedRevisions,
            c.version(),
            c.createdBy(),
            c.createdAt(),
            c.updatedAt()
        );
    }

    private ClaimSummary toSummary(StoredClaim c,
                                   List<StoredRevision> revisions,
                                   List<StoredSubmissionEvent> events) {
        Integer activeRevNum = null;
        Money activeAmount = null;
        boolean activeSubmitted = false;
        if (c.activeRevisionId() != null) {
            for (StoredRevision r : revisions) {
                if (r.id().equals(c.activeRevisionId())) {
                    activeRevNum = r.revisionNumber();
                    activeAmount = ClaimDomain.toMoney(r.submittedAmount(), c.currency(), c.currencyScale());
                    break;
                }
            }
            activeSubmitted = ClaimDomain.deriveRevisionSubmissionSummary(c.activeRevisionId(), events)
                .state() == RevisionSubmissionState.SUBMITTED;
        }
        return new ClaimSummary(
            c.id(),
            c.clinicId(),
            c.payerId(),
            c.externalReference(),
            c.pseudonymousPatientRef(),
            c.currency(),
            c.currencyScale(),
            c.state(),
            c.draftSourceDate(),
            c.activeRevisionId(),
            activeRevNum,
            activeAmount,
            activeSubmitted,
            c.version(),
            c.createdAt(),
            c.updatedAt()
        );
    }

    private static List<ClaimSubmissionEvent> mapSubmissionEvents(List<StoredSubmissionEvent> events) {
        Set<UUID> supersededIds = new HashSet<>();
        for (StoredSubmissionEvent e : events) {
            if (e.supersedesId() != null) supersededIds.add(e.supersedesId());
        }
        return events.stream()
            .map(e -> toSubmissionDto(e, supersededIds.contains(e.id())))
            .toList();
    }

    private static ClaimSubmissionEvent mapSingleSubmissionEvent(StoredSubmissionEvent target,
                                                                 List<StoredSubmissionEvent> events) {
        Set<UUID> supersededIds = new HashSet<>();
        for (StoredSubmissionEvent e : events) {
            if (e.supersedesId() != null) supersededIds.add(e.supersedesId());
        }
        return toSubmissionDto(target, supersededIds.contains(target.id()));
    }

    private static ClaimSubmissionEvent toSubmissionDto(StoredSubmissionEvent e, boolean superseded) {
        return new ClaimSubmissionEvent(
            e.id(),
            e.clinicId(),
            e.claimId(),
            e.revisionId(),
            e.revisionNumber(),
            e.eventKind(),
            e.channelCode(),
            e.externalSubmissionRef(),
            e.occurredOn(),
            e.occurredAt(),
            e.occurrencePrecision(),
            e.evidenceDocumentId(),
            e.resubmissionOfId(),
            e.supersedesId(),
            superseded,
            e.correctionReason(),
            e.recordedBy(),
            e.recordedAt()
        );
    }

    private static void validatePage(int page) {
        if (page < 0 || page > 10000) throw new Failure(400, "INVALID_PAGE");
    }
}
