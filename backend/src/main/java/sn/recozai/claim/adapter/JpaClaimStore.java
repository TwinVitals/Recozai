package sn.recozai.claim.adapter;

import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.claim.application.ClaimStore;
import sn.recozai.claim.domain.ClaimDomain.*;
import sn.recozai.platform.Database;
import sn.recozai.shared.Failure;
import java.util.*;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaClaimStore implements ClaimStore {
    private static final int PAGE_SIZE = 25;
    private static final String CLAIM_COLUMNS = """
        id, clinic_id, payer_id, external_reference, pseudonymous_patient_ref,
        currency, currency_scale, state, draft_source_date, active_revision_id,
        state_reason, version, created_by, created_at, updated_at
        """;

    private final Database db;

    public JpaClaimStore(Database db) {
        this.db = db;
    }

    @Override
    public PageResult<StoredClaim> claims(UUID clinicId, UUID payerId, ClaimState state, int page) {
        String payerFilter = payerId == null ? null : payerId.toString();
        String stateFilter = state == null ? null : state.name();
        long total = number(db.rows("""
            SELECT COUNT(*) AS total FROM claim
            WHERE clinic_id = :c
              AND (CAST(:p AS uuid) IS NULL OR payer_id = CAST(:p AS uuid))
              AND (CAST(:s AS varchar) IS NULL OR state = CAST(:s AS varchar))
            """,
            "c", clinicId, "p", payerFilter, "s", stateFilter
        ).getFirst(), "total");

        List<StoredClaim> items = db.rows(
            "SELECT " + CLAIM_COLUMNS + """
             FROM claim
            WHERE clinic_id = :c
              AND (CAST(:p AS uuid) IS NULL OR payer_id = CAST(:p AS uuid))
              AND (CAST(:s AS varchar) IS NULL OR state = CAST(:s AS varchar))
            ORDER BY updated_at DESC, id DESC
            LIMIT :l OFFSET :o
            """,
            "c", clinicId, "p", payerFilter, "s", stateFilter, "l", PAGE_SIZE, "o", page * PAGE_SIZE
        ).stream().map(JpaClaimStore::toClaim).toList();
        return new PageResult<>(items, total);
    }

    @Override
    public Optional<StoredClaim> claim(UUID clinicId, UUID id) {
        return db.rows("SELECT " + CLAIM_COLUMNS + " FROM claim WHERE clinic_id = :c AND id = :id",
                "c", clinicId, "id", id)
            .stream().findFirst().map(JpaClaimStore::toClaim);
    }

    @Override
    public Optional<StoredClaim> lockClaim(UUID clinicId, UUID id) {
        return db.rows("SELECT " + CLAIM_COLUMNS + " FROM claim WHERE clinic_id = :c AND id = :id FOR UPDATE",
                "c", clinicId, "id", id)
            .stream().findFirst().map(JpaClaimStore::toClaim);
    }

    @Override
    public boolean externalReferenceExists(UUID clinicId, UUID payerId, String externalReference, UUID excludeClaimId) {
        if (excludeClaimId == null) {
            return !db.rows("""
                SELECT id FROM claim
                WHERE clinic_id = :c AND payer_id = :p AND external_reference = :r
                """, "c", clinicId, "p", payerId, "r", externalReference).isEmpty();
        }
        return !db.rows("""
            SELECT id FROM claim
            WHERE clinic_id = :c AND payer_id = :p AND external_reference = :r AND id <> :ex
            """, "c", clinicId, "p", payerId, "r", externalReference, "ex", excludeClaimId).isEmpty();
    }

    @Override
    public void createDraftClaim(StoredClaim c, List<ValidatedLine> draftLines) {
        db.execute("""
            INSERT INTO claim(
                id, clinic_id, payer_id, external_reference, pseudonymous_patient_ref,
                currency, currency_scale, state, draft_source_date, active_revision_id,
                state_reason, version, created_by, created_at, updated_at
            ) VALUES (
                :id, :c, :p, :r, :pt,
                :cur, :sc, :st, CAST(:sd AS date), CAST(:ar AS uuid),
                :sr, :v, :cb, :ca, :ua
            )
            """,
            "id", c.id(), "c", c.clinicId(), "p", c.payerId(),
            "r", c.externalReference(), "pt", c.pseudonymousPatientRef(),
            "cur", c.currency(), "sc", c.currencyScale(), "st", c.state().name(),
            "sd", c.draftSourceDate() == null ? null : c.draftSourceDate().toString(),
            "ar", c.activeRevisionId() == null ? null : c.activeRevisionId().toString(),
            "sr", c.stateReason(), "v", c.version(),
            "cb", c.createdBy(), "ca", c.createdAt(), "ua", c.updatedAt()
        );
        insertDraftLines(c.clinicId(), c.id(), draftLines);
    }

    @Override
    public void updateDraftClaim(StoredClaim c, List<ValidatedLine> draftLines, long expectedVersion) {
        int updated = db.execute("""
            UPDATE claim
            SET payer_id = :p,
                external_reference = :r,
                pseudonymous_patient_ref = :pt,
                draft_source_date = CAST(:sd AS date),
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id AND version = :ev
            """,
            "p", c.payerId(), "r", c.externalReference(), "pt", c.pseudonymousPatientRef(),
            "sd", c.draftSourceDate() == null ? null : c.draftSourceDate().toString(),
            "nv", c.version(), "ua", c.updatedAt(),
            "c", c.clinicId(), "id", c.id(), "ev", expectedVersion
        );
        if (updated != 1) throw optimisticFailure(c.clinicId(), c.id());
        db.execute("DELETE FROM claim_draft_line WHERE clinic_id = :c AND claim_id = :id",
            "c", c.clinicId(), "id", c.id());
        insertDraftLines(c.clinicId(), c.id(), draftLines);
    }

    @Override
    public List<ValidatedLine> draftLines(UUID clinicId, UUID claimId) {
        return db.rows("""
            SELECT id, line_number, service_code, description, submitted_amount
            FROM claim_draft_line
            WHERE clinic_id = :c AND claim_id = :id
            ORDER BY line_number ASC
            """, "c", clinicId, "id", claimId)
            .stream().map(JpaClaimStore::toLine).toList();
    }

    @Override
    public List<StoredRevision> revisions(UUID clinicId, UUID claimId) {
        List<Tuple> revRows = db.rows("""
            SELECT id, clinic_id, claim_id, revision_number, submitted_amount,
                   source_date, content_hash, change_reason, evidence_document_id,
                   registered_by, registered_at
            FROM claim_revision
            WHERE clinic_id = :c AND claim_id = :id
            ORDER BY revision_number ASC
            """, "c", clinicId, "id", claimId);
        if (revRows.isEmpty()) return List.of();

        List<Tuple> lineRows = db.rows("""
            SELECT id, revision_id, line_number, service_code, description, submitted_amount
            FROM claim_line
            WHERE clinic_id = :c AND claim_id = :id
            ORDER BY line_number ASC
            """, "c", clinicId, "id", claimId);
        Map<UUID, List<ValidatedLine>> linesByRevision = new HashMap<>();
        for (Tuple row : lineRows) {
            UUID revId = uuid(row, "revision_id");
            linesByRevision.computeIfAbsent(revId, ignored -> new ArrayList<>()).add(toLine(row));
        }
        return revRows.stream()
            .map(row -> toRevision(row, linesByRevision.getOrDefault(uuid(row, "id"), List.of())))
            .toList();
    }

    @Override
    public Optional<StoredRevision> revision(UUID clinicId, UUID claimId, UUID revisionId) {
        return revisions(clinicId, claimId).stream()
            .filter(r -> r.id().equals(revisionId))
            .findFirst();
    }

    @Override
    public int nextRevisionNumber(UUID clinicId, UUID claimId) {
        var row = db.rows("""
            SELECT COALESCE(MAX(revision_number), 0) + 1 AS next_rev
            FROM claim_revision
            WHERE clinic_id = :c AND claim_id = :id
            """, "c", clinicId, "id", claimId).getFirst();
        return integer(row, "next_rev");
    }

    @Override
    public void registerFirstRevision(StoredClaim updatedClaim, StoredRevision firstRevision, long expectedVersion) {
        insertRevisionAndLines(firstRevision);
        int updated = db.execute("""
            UPDATE claim
            SET state = :st,
                active_revision_id = :ar,
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id AND version = :ev
            """,
            "st", updatedClaim.state().name(), "ar", firstRevision.id(),
            "nv", updatedClaim.version(), "ua", updatedClaim.updatedAt(),
            "c", updatedClaim.clinicId(), "id", updatedClaim.id(), "ev", expectedVersion
        );
        if (updated != 1) throw optimisticFailure(updatedClaim.clinicId(), updatedClaim.id());
        db.execute("DELETE FROM claim_draft_line WHERE clinic_id = :c AND claim_id = :id",
            "c", updatedClaim.clinicId(), "id", updatedClaim.id());
    }

    @Override
    public void appendAmendedRevision(StoredClaim updatedClaim, StoredRevision amendedRevision, long expectedVersion) {
        insertRevisionAndLines(amendedRevision);
        int updated = db.execute("""
            UPDATE claim
            SET active_revision_id = :ar,
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id AND version = :ev
            """,
            "ar", amendedRevision.id(), "nv", updatedClaim.version(),
            "ua", updatedClaim.updatedAt(),
            "c", updatedClaim.clinicId(), "id", updatedClaim.id(), "ev", expectedVersion
        );
        if (updated != 1) throw optimisticFailure(updatedClaim.clinicId(), updatedClaim.id());
    }

    @Override
    public void transitionClaimState(StoredClaim updatedClaim, long expectedVersion) {
        int updated = db.execute("""
            UPDATE claim
            SET state = :st,
                state_reason = :sr,
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id AND version = :ev
            """,
            "st", updatedClaim.state().name(), "sr", updatedClaim.stateReason(),
            "nv", updatedClaim.version(), "ua", updatedClaim.updatedAt(),
            "c", updatedClaim.clinicId(), "id", updatedClaim.id(), "ev", expectedVersion
        );
        if (updated != 1) throw optimisticFailure(updatedClaim.clinicId(), updatedClaim.id());
    }

    @Override
    public List<StoredSubmissionEvent> submissionEvents(UUID clinicId, UUID claimId) {
        return db.rows("""
            SELECT s.id, s.clinic_id, s.claim_id, s.revision_id, r.revision_number,
                   s.event_kind, s.channel_code, s.external_submission_ref,
                   s.occurred_on, s.occurred_at, s.occurrence_precision,
                   s.evidence_document_id, s.resubmission_of_id, s.supersedes_id,
                   s.correction_reason, s.idempotency_key, s.request_hash,
                   s.recorded_by, s.recorded_at
            FROM claim_submission s
            JOIN claim_revision r
              ON r.clinic_id = s.clinic_id
             AND r.claim_id = s.claim_id
             AND r.id = s.revision_id
            WHERE s.clinic_id = :c AND s.claim_id = :id
            ORDER BY s.recorded_at ASC, s.id ASC
            """, "c", clinicId, "id", claimId)
            .stream().map(JpaClaimStore::toSubmissionEvent).toList();
    }

    @Override
    public Optional<StoredSubmissionEvent> submissionEventByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey) {
        return db.rows("""
            SELECT s.id, s.clinic_id, s.claim_id, s.revision_id, r.revision_number,
                   s.event_kind, s.channel_code, s.external_submission_ref,
                   s.occurred_on, s.occurred_at, s.occurrence_precision,
                   s.evidence_document_id, s.resubmission_of_id, s.supersedes_id,
                   s.correction_reason, s.idempotency_key, s.request_hash,
                   s.recorded_by, s.recorded_at
            FROM claim_submission s
            JOIN claim_revision r
              ON r.clinic_id = s.clinic_id
             AND r.claim_id = s.claim_id
             AND r.id = s.revision_id
            WHERE s.clinic_id = :c AND s.claim_id = :id AND s.idempotency_key = :ik
            """, "c", clinicId, "id", claimId, "ik", idempotencyKey)
            .stream().findFirst().map(JpaClaimStore::toSubmissionEvent);
    }

    @Override
    public void appendSubmissionEvent(StoredSubmissionEvent e, ClaimState newClaimState) {
        db.execute("""
            INSERT INTO claim_submission(
                id, clinic_id, claim_id, revision_id, event_kind,
                channel_code, external_submission_ref, occurred_on, occurred_at,
                occurrence_precision, evidence_document_id, resubmission_of_id,
                supersedes_id, correction_reason, idempotency_key, request_hash,
                recorded_by, recorded_at
            ) VALUES (
                :id, :c, :cid, :rid, :ek,
                :cc, :ref, CAST(:oo AS date), CAST(:oa AS timestamptz),
                :op, CAST(:ed AS uuid), CAST(:ro AS uuid),
                CAST(:su AS uuid), :cr, :ik, :rh,
                :rb, :ra
            )
            """,
            "id", e.id(), "c", e.clinicId(), "cid", e.claimId(), "rid", e.revisionId(),
            "ek", e.eventKind().name(), "cc", e.channelCode(), "ref", e.externalSubmissionRef(),
            "oo", e.occurredOn().toString(),
            "oa", e.occurredAt() == null ? null : e.occurredAt().toString(),
            "op", e.occurrencePrecision().name(),
            "ed", e.evidenceDocumentId() == null ? null : e.evidenceDocumentId().toString(),
            "ro", e.resubmissionOfId() == null ? null : e.resubmissionOfId().toString(),
            "su", e.supersedesId() == null ? null : e.supersedesId().toString(),
            "cr", e.correctionReason(), "ik", e.idempotencyKey(), "rh", e.requestHash(),
            "rb", e.recordedBy(), "ra", e.recordedAt()
        );
        db.execute("""
            UPDATE claim
            SET state = :st,
                version = version + 1,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id
            """,
            "st", newClaimState.name(), "ua", e.recordedAt(),
            "c", e.clinicId(), "id", e.claimId()
        );
    }

    private void insertDraftLines(UUID clinicId, UUID claimId, List<ValidatedLine> lines) {
        for (ValidatedLine line : lines) {
            db.execute("""
                INSERT INTO claim_draft_line(
                    id, clinic_id, claim_id, line_number, service_code, description, submitted_amount
                ) VALUES (:id, :c, :cid, :ln, :sc, :ds, :am)
                """,
                "id", line.id(), "c", clinicId, "cid", claimId,
                "ln", line.lineNumber(), "sc", line.serviceCode(),
                "ds", line.description(), "am", line.submittedAmount()
            );
        }
    }

    private void insertRevisionAndLines(StoredRevision r) {
        db.execute("""
            INSERT INTO claim_revision(
                id, clinic_id, claim_id, revision_number, submitted_amount,
                source_date, content_hash, change_reason, evidence_document_id,
                registered_by, registered_at
            ) VALUES (
                :id, :c, :cid, :rn, :am,
                CAST(:sd AS date), :ch, :cr, CAST(:ed AS uuid),
                :rb, :ra
            )
            """,
            "id", r.id(), "c", r.clinicId(), "cid", r.claimId(),
            "rn", r.revisionNumber(), "am", r.submittedAmount(),
            "sd", r.sourceDate().toString(), "ch", r.contentHash(),
            "cr", r.changeReason(),
            "ed", r.evidenceDocumentId() == null ? null : r.evidenceDocumentId().toString(),
            "rb", r.registeredBy(), "ra", r.registeredAt()
        );
        for (ValidatedLine line : r.lines()) {
            db.execute("""
                INSERT INTO claim_line(
                    id, clinic_id, claim_id, revision_id, line_number,
                    service_code, description, submitted_amount
                ) VALUES (:id, :c, :cid, :rid, :ln, :sc, :ds, :am)
                """,
                "id", line.id(), "c", r.clinicId(), "cid", r.claimId(), "rid", r.id(),
                "ln", line.lineNumber(), "sc", line.serviceCode(),
                "ds", line.description(), "am", line.submittedAmount()
            );
        }
    }

    private Failure optimisticFailure(UUID clinicId, UUID id) {
        return claim(clinicId, id).isEmpty()
            ? new Failure(404, "NOT_FOUND")
            : new Failure(412, "STALE_VERSION");
    }

    private static StoredClaim toClaim(Tuple row) {
        return new StoredClaim(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "payer_id"),
            text(row, "external_reference"),
            text(row, "pseudonymous_patient_ref"),
            text(row, "currency"),
            integer(row, "currency_scale"),
            ClaimState.valueOf(text(row, "state")),
            localDate(row, "draft_source_date"),
            uuid(row, "active_revision_id"),
            text(row, "state_reason"),
            number(row, "version"),
            uuid(row, "created_by"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static ValidatedLine toLine(Tuple row) {
        return new ValidatedLine(
            uuid(row, "id"),
            integer(row, "line_number"),
            text(row, "service_code"),
            text(row, "description"),
            decimal(row, "submitted_amount")
        );
    }

    private static StoredRevision toRevision(Tuple row, List<ValidatedLine> lines) {
        return new StoredRevision(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            integer(row, "revision_number"),
            decimal(row, "submitted_amount"),
            localDate(row, "source_date"),
            text(row, "content_hash"),
            text(row, "change_reason"),
            uuid(row, "evidence_document_id"),
            lines,
            uuid(row, "registered_by"),
            instant(row, "registered_at")
        );
    }

    private static StoredSubmissionEvent toSubmissionEvent(Tuple row) {
        return new StoredSubmissionEvent(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            uuid(row, "revision_id"),
            integer(row, "revision_number"),
            SubmissionEventKind.valueOf(text(row, "event_kind")),
            text(row, "channel_code"),
            text(row, "external_submission_ref"),
            localDate(row, "occurred_on"),
            instant(row, "occurred_at"),
            OccurrencePrecision.valueOf(text(row, "occurrence_precision")),
            uuid(row, "evidence_document_id"),
            uuid(row, "resubmission_of_id"),
            uuid(row, "supersedes_id"),
            text(row, "correction_reason"),
            text(row, "idempotency_key"),
            text(row, "request_hash"),
            uuid(row, "recorded_by"),
            instant(row, "recorded_at")
        );
    }
}
