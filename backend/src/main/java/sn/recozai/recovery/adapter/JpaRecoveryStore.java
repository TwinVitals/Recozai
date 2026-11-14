package sn.recozai.recovery.adapter;

import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import sn.recozai.platform.Database;
import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.recovery.application.RecoveryStore;
import sn.recozai.recovery.domain.RecoveryDomain.StoredLedgerEntry;
import sn.recozai.shared.Failure;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaRecoveryStore implements RecoveryStore {
    private static final int PAGE_SIZE = 25;
    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {};

    private final Database db;
    private final ObjectMapper json;

    public JpaRecoveryStore(Database db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public List<StoredLedgerEntry> ledgerEntries(UUID clinicId, UUID claimId, UUID revisionId) {
        return db.rows("""
            SELECT * FROM financial_journal_entry
            WHERE clinic_id = :c AND claim_id = :cid AND revision_id = :rid
            ORDER BY recorded_at ASC, id ASC
            """, "c", clinicId, "cid", claimId, "rid", revisionId)
            .stream().map(JpaRecoveryStore::toLedgerEntry).toList();
    }

    @Override
    public Optional<StoredLedgerEntry> ledgerEntryByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey) {
        return db.rows("""
            SELECT * FROM financial_journal_entry
            WHERE clinic_id = :c AND claim_id = :cid AND idempotency_key = :ik
            """, "c", clinicId, "cid", claimId, "ik", idempotencyKey)
            .stream().findFirst().map(JpaRecoveryStore::toLedgerEntry);
    }

    @Override
    public void appendLedgerEntry(StoredLedgerEntry e) {
        db.execute("""
            INSERT INTO financial_journal_entry(
                id, clinic_id, claim_id, revision_id, entry_type,
                amount, previous_outstanding_amount, resulting_outstanding_amount,
                currency, currency_scale, occurred_on, payer_reference,
                source_document_id, reverses_entry_id, reason,
                idempotency_key, request_hash, recorded_by, recorded_at
            ) VALUES (
                :id, :c, :cid, :rid, :et,
                :am, :poa, :roa,
                :cur, :sc, CAST(:oo AS date), :pr,
                CAST(:sd AS uuid), CAST(:re AS uuid), :rs,
                :ik, :rh, :rb, :ra
            )
            """,
            "id", e.id(), "c", e.clinicId(), "cid", e.claimId(), "rid", e.revisionId(),
            "et", e.entryType().name(),
            "am", e.amount(), "poa", e.previousOutstandingAmount(), "roa", e.resultingOutstandingAmount(),
            "cur", e.currency(), "sc", e.currencyScale(), "oo", e.occurredOn().toString(),
            "pr", e.payerReference(),
            "sd", e.sourceDocumentId() == null ? null : e.sourceDocumentId().toString(),
            "re", e.reversesEntryId() == null ? null : e.reversesEntryId().toString(),
            "rs", e.reason(), "ik", e.idempotencyKey(), "rh", e.requestHash(),
            "rb", e.recordedBy(), "ra", e.recordedAt()
        );
    }

    @Override
    public List<StoredDenialEvent> denialEventsByClaim(UUID clinicId, UUID claimId) {
        return db.rows("""
            SELECT * FROM denial_event
            WHERE clinic_id = :c AND claim_id = :cid
            ORDER BY recorded_at DESC, id DESC
            """, "c", clinicId, "cid", claimId)
            .stream().map(JpaRecoveryStore::toDenialEvent).toList();
    }

    @Override
    public Optional<StoredDenialEvent> denialEvent(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM denial_event WHERE clinic_id = :c AND id = :id",
                "c", clinicId, "id", id)
            .stream().findFirst().map(JpaRecoveryStore::toDenialEvent);
    }

    @Override
    public Optional<StoredDenialEvent> denialEventByIdempotencyKey(UUID clinicId, UUID claimId, String idempotencyKey) {
        return db.rows("""
            SELECT * FROM denial_event
            WHERE clinic_id = :c AND claim_id = :cid AND idempotency_key = :ik
            """, "c", clinicId, "cid", claimId, "ik", idempotencyKey)
            .stream().findFirst().map(JpaRecoveryStore::toDenialEvent);
    }

    @Override
    public void appendDenialEvent(StoredDenialEvent d) {
        db.execute("""
            INSERT INTO denial_event(
                id, clinic_id, claim_id, revision_id, payer_id, ledger_entry_id,
                denial_kind, source_reason_code, original_reason_text,
                normalized_category_key, category_mapping_source, mapping_rule_version_id,
                denied_amount, currency, currency_scale, notice_date, received_date,
                payer_reference, source_document_id, idempotency_key, request_hash,
                recorded_by, recorded_at
            ) VALUES (
                :id, :c, :cid, :rid, :pid, :lid,
                :dk, :src, :ort,
                :nck, :cms, CAST(:mrv AS uuid),
                :da, :cur, :sc, CAST(:nd AS date), CAST(:rd AS date),
                :pr, CAST(:sd AS uuid), :ik, :rh,
                :rb, :ra
            )
            """,
            "id", d.id(), "c", d.clinicId(), "cid", d.claimId(), "rid", d.revisionId(),
            "pid", d.payerId(), "lid", d.ledgerEntryId(),
            "dk", d.denialKind().name(), "src", d.sourceReasonCode(), "ort", d.originalReasonText(),
            "nck", d.normalizedCategoryKey(), "cms", d.categoryMappingSource().name(),
            "mrv", d.mappingRuleVersionId() == null ? null : d.mappingRuleVersionId().toString(),
            "da", d.deniedAmount(), "cur", d.currency(), "sc", d.currencyScale(),
            "nd", d.noticeDate().toString(), "rd", d.receivedDate().toString(),
            "pr", d.payerReference(),
            "sd", d.sourceDocumentId() == null ? null : d.sourceDocumentId().toString(),
            "ik", d.idempotencyKey(), "rh", d.requestHash(),
            "rb", d.recordedBy(), "ra", d.recordedAt()
        );
    }

    @Override
    public PageResult<StoredRecoveryCase> recoveryCases(UUID clinicId, UUID payerId, DenialCaseStatus status, int page) {
        String payerFilter = payerId == null ? null : payerId.toString();
        String statusFilter = status == null ? null : status.name();
        long total = number(db.rows("""
            SELECT COUNT(*) AS total FROM recovery_case
            WHERE clinic_id = :c
              AND (CAST(:p AS uuid) IS NULL OR payer_id = CAST(:p AS uuid))
              AND (CAST(:s AS varchar) IS NULL OR case_status = CAST(:s AS varchar))
            """, "c", clinicId, "p", payerFilter, "s", statusFilter).getFirst(), "total");

        List<StoredRecoveryCase> items = db.rows("""
            SELECT * FROM recovery_case
            WHERE clinic_id = :c
              AND (CAST(:p AS uuid) IS NULL OR payer_id = CAST(:p AS uuid))
              AND (CAST(:s AS varchar) IS NULL OR case_status = CAST(:s AS varchar))
            ORDER BY due_date ASC NULLS LAST, updated_at DESC, id DESC
            LIMIT :l OFFSET :o
            """, "c", clinicId, "p", payerFilter, "s", statusFilter,
            "l", PAGE_SIZE, "o", page * PAGE_SIZE)
            .stream().map(JpaRecoveryStore::toRecoveryCase).toList();
        return new PageResult<>(items, total);
    }

    @Override
    public Optional<StoredRecoveryCase> recoveryCase(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM recovery_case WHERE clinic_id = :c AND id = :id",
                "c", clinicId, "id", id)
            .stream().findFirst().map(JpaRecoveryStore::toRecoveryCase);
    }

    @Override
    public Optional<StoredRecoveryCase> recoveryCaseByDenialEvent(UUID clinicId, UUID primaryDenialEventId) {
        return db.rows("""
            SELECT * FROM recovery_case
            WHERE clinic_id = :c AND primary_denial_event_id = :did
            """, "c", clinicId, "did", primaryDenialEventId)
            .stream().findFirst().map(JpaRecoveryStore::toRecoveryCase);
    }

    @Override
    public Optional<StoredRecoveryCase> lockRecoveryCase(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM recovery_case WHERE clinic_id = :c AND id = :id FOR UPDATE",
                "c", clinicId, "id", id)
            .stream().findFirst().map(JpaRecoveryStore::toRecoveryCase);
    }

    @Override
    public void createRecoveryCase(StoredRecoveryCase rc) {
        db.execute("""
            INSERT INTO recovery_case(
                id, clinic_id, claim_id, revision_id, payer_id, primary_denial_event_id,
                case_status, recovery_outcome, assigned_to_user_id,
                due_date, due_date_basis, due_date_rule_version_id, due_date_source_note,
                estimated_recoverable_amount, estimate_basis, estimate_note,
                closure_reason, version, created_by, created_at, updated_at
            ) VALUES (
                :id, :c, :cid, :rid, :pid, :did,
                :cs, :ro, CAST(:au AS uuid),
                CAST(:dd AS date), :ddb, CAST(:ddrv AS uuid), :ddsn,
                :era, :eb, :en,
                :cr, :v, :cb, :ca, :ua
            )
            """,
            "id", rc.id(), "c", rc.clinicId(), "cid", rc.claimId(), "rid", rc.revisionId(),
            "pid", rc.payerId(), "did", rc.primaryDenialEventId(),
            "cs", rc.caseStatus().name(), "ro", rc.recoveryOutcome().name(),
            "au", rc.assignedToUserId() == null ? null : rc.assignedToUserId().toString(),
            "dd", rc.dueDate() == null ? null : rc.dueDate().toString(),
            "ddb", rc.dueDateBasis().name(),
            "ddrv", rc.dueDateRuleVersionId() == null ? null : rc.dueDateRuleVersionId().toString(),
            "ddsn", rc.dueDateSourceNote(),
            "era", rc.estimatedRecoverableAmount(),
            "eb", rc.estimateBasis().name(), "en", rc.estimateNote(),
            "cr", rc.closureReason(), "v", rc.version(),
            "cb", rc.createdBy(), "ca", rc.createdAt(), "ua", rc.updatedAt()
        );
    }

    @Override
    public void updateRecoveryCase(StoredRecoveryCase rc, long expectedVersion) {
        int updated = db.execute("""
            UPDATE recovery_case
            SET case_status = :cs,
                recovery_outcome = :ro,
                assigned_to_user_id = CAST(:au AS uuid),
                due_date = CAST(:dd AS date),
                due_date_basis = :ddb,
                due_date_rule_version_id = CAST(:ddrv AS uuid),
                due_date_source_note = :ddsn,
                estimated_recoverable_amount = :era,
                estimate_basis = :eb,
                estimate_note = :en,
                closure_reason = :cr,
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND id = :id AND version = :ev
            """,
            "cs", rc.caseStatus().name(), "ro", rc.recoveryOutcome().name(),
            "au", rc.assignedToUserId() == null ? null : rc.assignedToUserId().toString(),
            "dd", rc.dueDate() == null ? null : rc.dueDate().toString(),
            "ddb", rc.dueDateBasis().name(),
            "ddrv", rc.dueDateRuleVersionId() == null ? null : rc.dueDateRuleVersionId().toString(),
            "ddsn", rc.dueDateSourceNote(),
            "era", rc.estimatedRecoverableAmount(),
            "eb", rc.estimateBasis().name(), "en", rc.estimateNote(),
            "cr", rc.closureReason(), "nv", rc.version(), "ua", rc.updatedAt(),
            "c", rc.clinicId(), "id", rc.id(), "ev", expectedVersion
        );
        if (updated != 1) {
            throw recoveryCase(rc.clinicId(), rc.id()).isEmpty()
                ? new Failure(404, "NOT_FOUND")
                : new Failure(412, "STALE_VERSION");
        }
    }

    @Override
    public List<StoredRecoveryTask> tasks(UUID clinicId, UUID recoveryCaseId) {
        return db.rows("""
            SELECT * FROM recovery_task
            WHERE clinic_id = :c AND recovery_case_id = :rcid
            ORDER BY created_at ASC, id ASC
            """, "c", clinicId, "rcid", recoveryCaseId)
            .stream().map(JpaRecoveryStore::toTask).toList();
    }

    @Override
    public Optional<StoredRecoveryTask> task(UUID clinicId, UUID recoveryCaseId, UUID taskId) {
        return db.rows("""
            SELECT * FROM recovery_task
            WHERE clinic_id = :c AND recovery_case_id = :rcid AND id = :id
            """, "c", clinicId, "rcid", recoveryCaseId, "id", taskId)
            .stream().findFirst().map(JpaRecoveryStore::toTask);
    }

    @Override
    public void createTask(StoredRecoveryTask t) {
        db.execute("""
            INSERT INTO recovery_task(
                id, clinic_id, recovery_case_id, task_kind, title, description,
                required_document_key, satisfied_document_id, status,
                assigned_to_user_id, due_date, resolution_note,
                completed_by, completed_at, version, created_by, created_at, updated_at
            ) VALUES (
                :id, :c, :rcid, :tk, :ti, :ds,
                :rdk, CAST(:sdi AS uuid), :st,
                CAST(:au AS uuid), CAST(:dd AS date), :rn,
                CAST(:cb AS uuid), CAST(:ca AS timestamptz), :v, :crb, :cra, :ua
            )
            """,
            "id", t.id(), "c", t.clinicId(), "rcid", t.recoveryCaseId(),
            "tk", t.taskKind().name(), "ti", t.title(), "ds", t.description(),
            "rdk", t.requiredDocumentKey(),
            "sdi", t.satisfiedDocumentId() == null ? null : t.satisfiedDocumentId().toString(),
            "st", t.status().name(),
            "au", t.assignedToUserId() == null ? null : t.assignedToUserId().toString(),
            "dd", t.dueDate() == null ? null : t.dueDate().toString(),
            "rn", t.resolutionNote(),
            "cb", t.completedBy() == null ? null : t.completedBy().toString(),
            "ca", t.completedAt() == null ? null : t.completedAt().toString(),
            "v", t.version(), "crb", t.createdBy(), "cra", t.createdAt(), "ua", t.updatedAt()
        );
    }

    @Override
    public void updateTask(StoredRecoveryTask t, long expectedVersion) {
        int updated = db.execute("""
            UPDATE recovery_task
            SET satisfied_document_id = CAST(:sdi AS uuid),
                status = :st,
                assigned_to_user_id = CAST(:au AS uuid),
                due_date = CAST(:dd AS date),
                resolution_note = :rn,
                completed_by = CAST(:cb AS uuid),
                completed_at = CAST(:ca AS timestamptz),
                version = :nv,
                updated_at = :ua
            WHERE clinic_id = :c AND recovery_case_id = :rcid AND id = :id AND version = :ev
            """,
            "sdi", t.satisfiedDocumentId() == null ? null : t.satisfiedDocumentId().toString(),
            "st", t.status().name(),
            "au", t.assignedToUserId() == null ? null : t.assignedToUserId().toString(),
            "dd", t.dueDate() == null ? null : t.dueDate().toString(),
            "rn", t.resolutionNote(),
            "cb", t.completedBy() == null ? null : t.completedBy().toString(),
            "ca", t.completedAt() == null ? null : t.completedAt().toString(),
            "nv", t.version(), "ua", t.updatedAt(),
            "c", t.clinicId(), "rcid", t.recoveryCaseId(), "id", t.id(), "ev", expectedVersion
        );
        if (updated != 1) {
            throw task(t.clinicId(), t.recoveryCaseId(), t.id()).isEmpty()
                ? new Failure(404, "NOT_FOUND")
                : new Failure(412, "STALE_VERSION");
        }
    }

    @Override
    public List<StoredAppealDraft> appealDrafts(UUID clinicId, UUID recoveryCaseId) {
        return db.rows("""
            SELECT * FROM appeal_draft_version
            WHERE clinic_id = :c AND recovery_case_id = :rcid
            ORDER BY version_number DESC, id DESC
            """, "c", clinicId, "rcid", recoveryCaseId)
            .stream().map(this::toAppealDraft).toList();
    }

    @Override
    public Optional<StoredAppealDraft> appealDraft(UUID clinicId, UUID recoveryCaseId, UUID draftId) {
        return db.rows("""
            SELECT * FROM appeal_draft_version
            WHERE clinic_id = :c AND recovery_case_id = :rcid AND id = :id
            """, "c", clinicId, "rcid", recoveryCaseId, "id", draftId)
            .stream().findFirst().map(this::toAppealDraft);
    }

    @Override
    public int nextAppealVersionNumber(UUID clinicId, UUID recoveryCaseId) {
        var row = db.rows("""
            SELECT COALESCE(MAX(version_number), 0) + 1 AS next_version
            FROM appeal_draft_version
            WHERE clinic_id = :c AND recovery_case_id = :rcid
            """, "c", clinicId, "rcid", recoveryCaseId).getFirst();
        return integer(row, "next_version");
    }

    @Override
    public void createAppealDraft(StoredAppealDraft a) {
        String citedJson = json.writeValueAsString(a.citedEvidenceIds());
        db.execute("""
            INSERT INTO appeal_draft_version(
                id, clinic_id, recovery_case_id, version_number, status, locale,
                subject_line, body_text, factual_basis_summary, cited_evidence_ids_json,
                unresolved_placeholder_count, approved_by, approved_at, approval_note,
                rejected_by, rejected_at, rejection_reason, created_by, created_at
            ) VALUES (
                :id, :c, :rcid, :vn, :st, :loc,
                :sl, :bt, :fbs, :cej,
                :upc, CAST(:ab AS uuid), CAST(:aa AS timestamptz), :an,
                CAST(:rb AS uuid), CAST(:ra AS timestamptz), :rr, :cb, :ca
            )
            """,
            "id", a.id(), "c", a.clinicId(), "rcid", a.recoveryCaseId(),
            "vn", a.versionNumber(), "st", a.status().name(), "loc", a.locale(),
            "sl", a.subjectLine(), "bt", a.bodyText(), "fbs", a.factualBasisSummary(),
            "cej", citedJson, "upc", a.unresolvedPlaceholderCount(),
            "ab", a.approvedBy() == null ? null : a.approvedBy().toString(),
            "aa", a.approvedAt() == null ? null : a.approvedAt().toString(),
            "an", a.approvalNote(),
            "rb", a.rejectedBy() == null ? null : a.rejectedBy().toString(),
            "ra", a.rejectedAt() == null ? null : a.rejectedAt().toString(),
            "rr", a.rejectionReason(),
            "cb", a.createdBy(), "ca", a.createdAt()
        );
    }

    @Override
    public void updateAppealDraftStatus(StoredAppealDraft a) {
        db.execute("""
            UPDATE appeal_draft_version
            SET status = :st,
                approved_by = CAST(:ab AS uuid),
                approved_at = CAST(:aa AS timestamptz),
                approval_note = :an,
                rejected_by = CAST(:rb AS uuid),
                rejected_at = CAST(:ra AS timestamptz),
                rejection_reason = :rr
            WHERE clinic_id = :c AND recovery_case_id = :rcid AND id = :id
            """,
            "st", a.status().name(),
            "ab", a.approvedBy() == null ? null : a.approvedBy().toString(),
            "aa", a.approvedAt() == null ? null : a.approvedAt().toString(),
            "an", a.approvalNote(),
            "rb", a.rejectedBy() == null ? null : a.rejectedBy().toString(),
            "ra", a.rejectedAt() == null ? null : a.rejectedAt().toString(),
            "rr", a.rejectionReason(),
            "c", a.clinicId(), "rcid", a.recoveryCaseId(), "id", a.id()
        );
    }

    @Override
    public List<StoredAppealSubmission> appealSubmissions(UUID clinicId, UUID recoveryCaseId) {
        return db.rows("""
            SELECT * FROM appeal_submission_record
            WHERE clinic_id = :c AND recovery_case_id = :rcid
            ORDER BY recorded_at ASC, id ASC
            """, "c", clinicId, "rcid", recoveryCaseId)
            .stream().map(JpaRecoveryStore::toAppealSubmission).toList();
    }

    @Override
    public Optional<StoredAppealSubmission> appealSubmissionByIdempotencyKey(UUID clinicId,
                                                                             UUID recoveryCaseId,
                                                                             String idempotencyKey) {
        return db.rows("""
            SELECT * FROM appeal_submission_record
            WHERE clinic_id = :c AND recovery_case_id = :rcid AND idempotency_key = :ik
            """, "c", clinicId, "rcid", recoveryCaseId, "ik", idempotencyKey)
            .stream().findFirst().map(JpaRecoveryStore::toAppealSubmission);
    }

    @Override
    public void appendAppealSubmission(StoredAppealSubmission s) {
        db.execute("""
            INSERT INTO appeal_submission_record(
                id, clinic_id, recovery_case_id, appeal_draft_version_id,
                channel_code, external_submission_ref, occurred_on, occurred_at,
                occurrence_precision, evidence_document_id, notes,
                idempotency_key, request_hash, recorded_by, recorded_at
            ) VALUES (
                :id, :c, :rcid, :advid,
                :cc, :ref, CAST(:oo AS date), CAST(:oa AS timestamptz),
                :op, CAST(:ed AS uuid), :nt,
                :ik, :rh, :rb, :ra
            )
            """,
            "id", s.id(), "c", s.clinicId(), "rcid", s.recoveryCaseId(),
            "advid", s.appealDraftVersionId(), "cc", s.channelCode(),
            "ref", s.externalSubmissionRef(), "oo", s.occurredOn().toString(),
            "oa", s.occurredAt() == null ? null : s.occurredAt().toString(),
            "op", s.occurrencePrecision().name(),
            "ed", s.evidenceDocumentId() == null ? null : s.evidenceDocumentId().toString(),
            "nt", s.notes(), "ik", s.idempotencyKey(), "rh", s.requestHash(),
            "rb", s.recordedBy(), "ra", s.recordedAt()
        );
    }

    private static StoredLedgerEntry toLedgerEntry(Tuple row) {
        return new StoredLedgerEntry(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            uuid(row, "revision_id"),
            LedgerEntryType.valueOf(text(row, "entry_type")),
            decimal(row, "amount"),
            decimal(row, "previous_outstanding_amount"),
            decimal(row, "resulting_outstanding_amount"),
            text(row, "currency"),
            integer(row, "currency_scale"),
            localDate(row, "occurred_on"),
            text(row, "payer_reference"),
            uuid(row, "source_document_id"),
            uuid(row, "reverses_entry_id"),
            text(row, "reason"),
            text(row, "idempotency_key"),
            text(row, "request_hash"),
            uuid(row, "recorded_by"),
            instant(row, "recorded_at")
        );
    }

    private static StoredDenialEvent toDenialEvent(Tuple row) {
        return new StoredDenialEvent(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            uuid(row, "revision_id"),
            uuid(row, "payer_id"),
            uuid(row, "ledger_entry_id"),
            DenialKind.valueOf(text(row, "denial_kind")),
            text(row, "source_reason_code"),
            text(row, "original_reason_text"),
            text(row, "normalized_category_key"),
            CategoryMappingSource.valueOf(text(row, "category_mapping_source")),
            uuid(row, "mapping_rule_version_id"),
            decimal(row, "denied_amount"),
            text(row, "currency"),
            integer(row, "currency_scale"),
            localDate(row, "notice_date"),
            localDate(row, "received_date"),
            text(row, "payer_reference"),
            uuid(row, "source_document_id"),
            text(row, "idempotency_key"),
            text(row, "request_hash"),
            uuid(row, "recorded_by"),
            instant(row, "recorded_at")
        );
    }

    private static StoredRecoveryCase toRecoveryCase(Tuple row) {
        return new StoredRecoveryCase(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            uuid(row, "revision_id"),
            uuid(row, "payer_id"),
            uuid(row, "primary_denial_event_id"),
            DenialCaseStatus.valueOf(text(row, "case_status")),
            RecoveryOutcome.valueOf(text(row, "recovery_outcome")),
            uuid(row, "assigned_to_user_id"),
            localDate(row, "due_date"),
            DueDateBasis.valueOf(text(row, "due_date_basis")),
            uuid(row, "due_date_rule_version_id"),
            text(row, "due_date_source_note"),
            decimal(row, "estimated_recoverable_amount"),
            EstimateBasis.valueOf(text(row, "estimate_basis")),
            text(row, "estimate_note"),
            text(row, "closure_reason"),
            number(row, "version"),
            uuid(row, "created_by"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static StoredRecoveryTask toTask(Tuple row) {
        return new StoredRecoveryTask(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "recovery_case_id"),
            RecoveryTaskKind.valueOf(text(row, "task_kind")),
            text(row, "title"),
            text(row, "description"),
            text(row, "required_document_key"),
            uuid(row, "satisfied_document_id"),
            RecoveryTaskStatus.valueOf(text(row, "status")),
            uuid(row, "assigned_to_user_id"),
            localDate(row, "due_date"),
            text(row, "resolution_note"),
            uuid(row, "completed_by"),
            instant(row, "completed_at"),
            number(row, "version"),
            uuid(row, "created_by"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private StoredAppealDraft toAppealDraft(Tuple row) {
        List<UUID> citedIds = json.readValue(text(row, "cited_evidence_ids_json"), UUID_LIST_TYPE);
        return new StoredAppealDraft(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "recovery_case_id"),
            integer(row, "version_number"),
            AppealStatus.valueOf(text(row, "status")),
            text(row, "locale"),
            text(row, "subject_line"),
            text(row, "body_text"),
            text(row, "factual_basis_summary"),
            citedIds,
            integer(row, "unresolved_placeholder_count"),
            uuid(row, "approved_by"),
            instant(row, "approved_at"),
            text(row, "approval_note"),
            uuid(row, "rejected_by"),
            instant(row, "rejected_at"),
            text(row, "rejection_reason"),
            uuid(row, "created_by"),
            instant(row, "created_at")
        );
    }

    private static StoredAppealSubmission toAppealSubmission(Tuple row) {
        return new StoredAppealSubmission(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "recovery_case_id"),
            uuid(row, "appeal_draft_version_id"),
            text(row, "channel_code"),
            text(row, "external_submission_ref"),
            localDate(row, "occurred_on"),
            instant(row, "occurred_at"),
            OccurrencePrecision.valueOf(text(row, "occurrence_precision")),
            uuid(row, "evidence_document_id"),
            text(row, "notes"),
            text(row, "idempotency_key"),
            text(row, "request_hash"),
            uuid(row, "recorded_by"),
            instant(row, "recorded_at")
        );
    }
}
