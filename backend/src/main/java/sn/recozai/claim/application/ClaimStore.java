package sn.recozai.claim.application;

import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.claim.domain.ClaimDomain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public interface ClaimStore {
    record StoredClaim(UUID id, UUID clinicId, UUID payerId, String externalReference,
                       String pseudonymousPatientRef, String currency, int currencyScale,
                       ClaimState state, LocalDate draftSourceDate, UUID activeRevisionId,
                       String stateReason, long version, UUID createdBy,
                       Instant createdAt, Instant updatedAt) {}

    record StoredRevision(UUID id, UUID clinicId, UUID claimId, int revisionNumber,
                          BigDecimal submittedAmount, LocalDate sourceDate, String contentHash,
                          String changeReason, UUID evidenceDocumentId,
                          List<ValidatedLine> lines, UUID registeredBy, Instant registeredAt) {}

    record PageResult<T>(List<T> items, long totalElements) {}

    PageResult<StoredClaim> claims(UUID clinicId, UUID payerId, ClaimState state, int page);
    Optional<StoredClaim> claim(UUID clinicId, UUID id);
    Optional<StoredClaim> lockClaim(UUID clinicId, UUID id);
    boolean externalReferenceExists(UUID clinicId, UUID payerId, String externalReference, UUID excludeClaimId);

    void createDraftClaim(StoredClaim claim, List<ValidatedLine> draftLines);
    void updateDraftClaim(StoredClaim claim, List<ValidatedLine> draftLines, long expectedVersion);
    List<ValidatedLine> draftLines(UUID clinicId, UUID claimId);

    List<StoredRevision> revisions(UUID clinicId, UUID claimId);
    Optional<StoredRevision> revision(UUID clinicId, UUID claimId, UUID revisionId);
    int nextRevisionNumber(UUID clinicId, UUID claimId);

    void registerFirstRevision(StoredClaim updatedClaim, StoredRevision firstRevision, long expectedVersion);
    void appendAmendedRevision(StoredClaim updatedClaim, StoredRevision amendedRevision, long expectedVersion);
    void transitionClaimState(StoredClaim updatedClaim, long expectedVersion);

    List<StoredSubmissionEvent> submissionEvents(UUID clinicId, UUID claimId);
    Optional<StoredSubmissionEvent> submissionEventByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey);
    void appendSubmissionEvent(StoredSubmissionEvent event, ClaimState newClaimState);
}
