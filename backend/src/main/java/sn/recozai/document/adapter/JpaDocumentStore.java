package sn.recozai.document.adapter;

import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import sn.recozai.document.api.DocumentModels.*;
import sn.recozai.document.application.DocumentStore;
import sn.recozai.platform.Database;
import sn.recozai.shared.Failure;
import java.time.Instant;
import java.util.*;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaDocumentStore implements DocumentStore {
    private static final int PAGE_SIZE = 25;
    private static final String SELECT_COLUMNS = """
        SELECT id, clinic_id, claim_id, payer_id, storage_key, source_name, mime_type,
               byte_count, sha256_hash, quarantine_status, scan_status, scan_detail,
               page_count, image_width, image_height, version, uploaded_by, created_at, updated_at
        FROM document
        """;

    private final Database db;

    public JpaDocumentStore(Database db) {
        this.db = db;
    }

    @Override
    public void createDocument(StoredDocument stored) {
        DocumentMetadata doc = stored.metadata();
        db.execute("""
            INSERT INTO document (
                id, clinic_id, claim_id, payer_id, storage_key, source_name, mime_type,
                byte_count, sha256_hash, quarantine_status, scan_status, scan_detail,
                page_count, image_width, image_height, version, uploaded_by, created_at, updated_at
            ) VALUES (
                :id, :clinic, CAST(:claim AS uuid), CAST(:payer AS uuid), :storageKey, :sourceName, :mimeType,
                :byteCount, :sha256, :quarantine, :scanStatus, :scanDetail,
                CAST(:pageCount AS integer), CAST(:imageWidth AS integer), CAST(:imageHeight AS integer),
                :version, :uploadedBy, :createdAt, :updatedAt
            )
            """,
            "id", doc.id(),
            "clinic", doc.clinicId(),
            "claim", doc.claimId() == null ? null : doc.claimId().toString(),
            "payer", doc.payerId() == null ? null : doc.payerId().toString(),
            "storageKey", stored.storageKey(),
            "sourceName", doc.sourceName(),
            "mimeType", doc.mimeType(),
            "byteCount", doc.byteCount(),
            "sha256", doc.sha256Hash(),
            "quarantine", doc.quarantineStatus().name(),
            "scanStatus", doc.scanStatus().name(),
            "scanDetail", doc.scanDetail(),
            "pageCount", doc.pageCount() == null ? null : doc.pageCount().toString(),
            "imageWidth", doc.imageWidth() == null ? null : doc.imageWidth().toString(),
            "imageHeight", doc.imageHeight() == null ? null : doc.imageHeight().toString(),
            "version", doc.version(),
            "uploadedBy", doc.uploadedBy(),
            "createdAt", doc.createdAt(),
            "updatedAt", doc.updatedAt()
        );
    }

    @Override
    public Optional<StoredDocument> document(UUID clinicId, UUID documentId) {
        return db.rows(
            SELECT_COLUMNS + " WHERE clinic_id = :clinic AND id = :id",
            "clinic", clinicId, "id", documentId
        ).stream().findFirst().map(this::mapStoredDocument);
    }

    @Override
    public Optional<StoredDocument> lockDocument(UUID clinicId, UUID documentId) {
        return db.rows(
            SELECT_COLUMNS + " WHERE clinic_id = :clinic AND id = :id FOR UPDATE",
            "clinic", clinicId, "id", documentId
        ).stream().findFirst().map(this::mapStoredDocument);
    }

    @Override
    public void updateScanState(UUID clinicId, UUID documentId, String storageKey,
                                QuarantineStatus quarantineStatus, ScanStatus scanStatus,
                                String scanDetail, Instant updatedAt, long expectedVersion) {
        int updated = db.execute("""
            UPDATE document
            SET storage_key = :storageKey,
                quarantine_status = :quarantine,
                scan_status = :scanStatus,
                scan_detail = :scanDetail,
                version = version + 1,
                updated_at = :updatedAt
            WHERE clinic_id = :clinic AND id = :id AND version = :expectedVersion
            """,
            "storageKey", storageKey,
            "quarantine", quarantineStatus.name(),
            "scanStatus", scanStatus.name(),
            "scanDetail", scanDetail,
            "updatedAt", updatedAt,
            "clinic", clinicId,
            "id", documentId,
            "expectedVersion", expectedVersion
        );
        if (updated == 0) {
            throw new Failure(412, "STALE_VERSION");
        }
    }

    @Override
    public PageResult<DocumentMetadata> claimDocuments(UUID clinicId, UUID claimId, int page) {
        long total = number(db.rows(
            "SELECT count(*) AS total FROM document WHERE clinic_id = :clinic AND claim_id = :claim",
            "clinic", clinicId, "claim", claimId
        ).getFirst(), "total");

        List<DocumentMetadata> items = db.rows(
            SELECT_COLUMNS + """
                WHERE clinic_id = :clinic AND claim_id = :claim
                ORDER BY created_at DESC, id ASC
                LIMIT :limit OFFSET :offset
                """,
            "clinic", clinicId, "claim", claimId,
            "limit", PAGE_SIZE, "offset", page * PAGE_SIZE
        ).stream().map(row -> mapStoredDocument(row).metadata()).toList();
        return new PageResult<>(items, total);
    }

    @Override
    public PageResult<DocumentMetadata> payerDocuments(UUID clinicId, UUID payerId, int page) {
        long total = number(db.rows(
            "SELECT count(*) AS total FROM document WHERE clinic_id = :clinic AND payer_id = :payer",
            "clinic", clinicId, "payer", payerId
        ).getFirst(), "total");

        List<DocumentMetadata> items = db.rows(
            SELECT_COLUMNS + """
                WHERE clinic_id = :clinic AND payer_id = :payer
                ORDER BY created_at DESC, id ASC
                LIMIT :limit OFFSET :offset
                """,
            "clinic", clinicId, "payer", payerId,
            "limit", PAGE_SIZE, "offset", page * PAGE_SIZE
        ).stream().map(row -> mapStoredDocument(row).metadata()).toList();
        return new PageResult<>(items, total);
    }

    @Override
    public List<EvidenceVersion> evidenceVersions(UUID clinicId, UUID documentId) {
        return db.rows("""
            SELECT id, clinic_id, document_id, version_number, page_number, region_label,
                   extracted_text, extraction_method, quality_flag, reviewer_correction,
                   correction_reason, supersedes_id, created_by, created_at
            FROM evidence_version
            WHERE clinic_id = :clinic AND document_id = :doc
            ORDER BY version_number ASC, id ASC
            """,
            "clinic", clinicId, "doc", documentId
        ).stream().map(this::mapEvidenceVersion).toList();
    }

    @Override
    public Optional<EvidenceVersion> evidenceVersion(UUID clinicId, UUID evidenceId) {
        return db.rows("""
            SELECT id, clinic_id, document_id, version_number, page_number, region_label,
                   extracted_text, extraction_method, quality_flag, reviewer_correction,
                   correction_reason, supersedes_id, created_by, created_at
            FROM evidence_version
            WHERE clinic_id = :clinic AND id = :id
            """,
            "clinic", clinicId, "id", evidenceId
        ).stream().findFirst().map(this::mapEvidenceVersion);
    }

    @Override
    public boolean isSuperseded(UUID clinicId, UUID evidenceId) {
        return !db.rows(
            "SELECT id FROM evidence_version WHERE clinic_id = :clinic AND supersedes_id = :id",
            "clinic", clinicId, "id", evidenceId
        ).isEmpty();
    }

    @Override
    public int nextEvidenceVersionNumber(UUID clinicId, UUID documentId) {
        var row = db.rows(
            "SELECT coalesce(max(version_number), 0) + 1 AS next_version FROM evidence_version WHERE clinic_id = :clinic AND document_id = :doc",
            "clinic", clinicId, "doc", documentId
        ).getFirst();
        return integer(row, "next_version");
    }

    @Override
    public void createEvidenceVersion(EvidenceVersion ev) {
        db.execute("""
            INSERT INTO evidence_version (
                id, clinic_id, document_id, version_number, page_number, region_label,
                extracted_text, extraction_method, quality_flag, reviewer_correction,
                correction_reason, supersedes_id, created_by, created_at
            ) VALUES (
                :id, :clinic, :doc, :versionNumber, :pageNumber, :regionLabel,
                :extractedText, :extractionMethod, :qualityFlag, :reviewerCorrection,
                :correctionReason, CAST(:supersedesId AS uuid), :createdBy, :createdAt
            )
            """,
            "id", ev.id(),
            "clinic", ev.clinicId(),
            "doc", ev.documentId(),
            "versionNumber", ev.versionNumber(),
            "pageNumber", ev.pageNumber(),
            "regionLabel", ev.regionLabel(),
            "extractedText", ev.extractedText(),
            "extractionMethod", ev.extractionMethod(),
            "qualityFlag", ev.qualityFlag().name(),
            "reviewerCorrection", ev.reviewerCorrection(),
            "correctionReason", ev.correctionReason(),
            "supersedesId", ev.supersedesId() == null ? null : ev.supersedesId().toString(),
            "createdBy", ev.createdBy(),
            "createdAt", ev.createdAt()
        );
    }

    private StoredDocument mapStoredDocument(Tuple row) {
        String storageKey = text(row, "storage_key");
        Object rawPageCount = row.get("page_count");
        Object rawWidth = row.get("image_width");
        Object rawHeight = row.get("image_height");
        DocumentMetadata metadata = new DocumentMetadata(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "claim_id"),
            uuid(row, "payer_id"),
            text(row, "source_name"),
            text(row, "mime_type"),
            number(row, "byte_count"),
            text(row, "sha256_hash"),
            QuarantineStatus.valueOf(text(row, "quarantine_status")),
            ScanStatus.valueOf(text(row, "scan_status")),
            text(row, "scan_detail"),
            rawPageCount == null ? null : ((Number) rawPageCount).intValue(),
            rawWidth == null ? null : ((Number) rawWidth).intValue(),
            rawHeight == null ? null : ((Number) rawHeight).intValue(),
            number(row, "version"),
            uuid(row, "uploaded_by"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
        return new StoredDocument(metadata, storageKey);
    }

    private EvidenceVersion mapEvidenceVersion(Tuple row) {
        return new EvidenceVersion(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "document_id"),
            integer(row, "version_number"),
            integer(row, "page_number"),
            text(row, "region_label"),
            text(row, "extracted_text"),
            text(row, "extraction_method"),
            QualityFlag.valueOf(text(row, "quality_flag")),
            flag(row, "reviewer_correction"),
            text(row, "correction_reason"),
            uuid(row, "supersedes_id"),
            uuid(row, "created_by"),
            instant(row, "created_at")
        );
    }
}
