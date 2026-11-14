package sn.recozai.claim.api;

import java.time.*;
import java.util.*;

public final class ClaimModels {
    private ClaimModels() {}

    public enum ClaimState { DRAFT, REGISTERED, SUBMITTED, VOIDED, ARCHIVED }
    public enum RevisionSubmissionState { UNSUBMITTED, SUBMITTED }
    public enum SubmissionEventKind { SUBMISSION, RESUBMISSION, METADATA_CORRECTION }
    public enum OccurrencePrecision { DATE, INSTANT }
    public enum ClaimTransitionAction { VOID, ARCHIVE, REOPEN }

    public record Money(String amount, String currency) {}

    public record ClaimLineInput(String serviceCode, String description, String amount) {}

    public record ClaimLine(UUID id, int lineNumber, String serviceCode,
                            String description, Money submittedAmount) {}

    public record ClaimRevision(UUID id, int revisionNumber, Money submittedAmount,
                                LocalDate sourceDate, String contentHash,
                                String changeReason, UUID evidenceDocumentId,
                                RevisionSubmissionState submissionState,
                                int externalSubmissionCount,
                                String effectiveChannelCode,
                                String effectiveExternalSubmissionRef,
                                LocalDate effectiveOccurredOn,
                                Instant effectiveOccurredAt,
                                OccurrencePrecision effectiveOccurrencePrecision,
                                List<ClaimLine> lines,
                                UUID registeredBy, Instant registeredAt) {
        public ClaimRevision {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record ClaimSummary(UUID id, UUID clinicId, UUID payerId,
                               String externalReference, String pseudonymousPatientRef,
                               String currency, int currencyScale, ClaimState state,
                               LocalDate draftSourceDate, UUID activeRevisionId,
                               Integer activeRevisionNumber, Money activeSubmittedAmount,
                               boolean activeRevisionSubmitted, long version,
                               Instant createdAt, Instant updatedAt) {}

    public record ClaimDetail(UUID id, UUID clinicId, UUID payerId,
                              String externalReference, String pseudonymousPatientRef,
                              String currency, int currencyScale, ClaimState state,
                              LocalDate draftSourceDate, UUID activeRevisionId,
                              boolean activeRevisionSubmitted, String stateReason,
                              List<ClaimLine> draftLines, List<ClaimRevision> revisions,
                              long version, UUID createdBy, Instant createdAt, Instant updatedAt) {
        public ClaimDetail {
            draftLines = draftLines == null ? List.of() : List.copyOf(draftLines);
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
        }
    }

    public record ClaimSubmissionEvent(UUID id, UUID clinicId, UUID claimId,
                                       UUID revisionId, int revisionNumber,
                                       SubmissionEventKind eventKind,
                                       String channelCode, String externalSubmissionRef,
                                       LocalDate occurredOn, Instant occurredAt,
                                       OccurrencePrecision occurrencePrecision,
                                       UUID evidenceDocumentId, UUID resubmissionOfId,
                                       UUID supersedesId, boolean superseded,
                                       String correctionReason,
                                       UUID recordedBy, Instant recordedAt) {}
}
