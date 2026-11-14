package sn.recozai.document.application;

import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.document.api.DocumentModels.*;
import sn.recozai.document.application.DocumentStore.StoredDocument;
import sn.recozai.document.domain.DocumentDomain;
import sn.recozai.document.domain.DocumentDomain.ExtractedSegment;
import sn.recozai.document.domain.DocumentDomain.ValidatedCorrection;
import sn.recozai.document.domain.DocumentDomain.ValidatedUpload;
import sn.recozai.shared.*;
import java.time.*;
import java.util.*;

public final class DocumentService {
    public interface ParentAccessPort {
        boolean claimExists(UUID clinicId, UUID claimId);
        boolean payerExists(UUID clinicId, UUID payerId);
    }

    private final DocumentStore store;
    private final DocumentStorage storage;
    private final DocumentScanner scanner;
    private final ParentAccessPort parents;
    private final Transactions transactions;
    private final Access access;
    private final Audit audit;
    private final Clock clock;

    public DocumentService(DocumentStore store, DocumentStorage storage, DocumentScanner scanner,
                           ParentAccessPort parents, Transactions transactions,
                           Access access, Audit audit, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.scanner = scanner;
        this.parents = parents;
        this.transactions = transactions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean isCleanPayerDocument(UUID clinicId, UUID payerId, UUID documentId) {
        if (clinicId == null || payerId == null || documentId == null) return false;
        return store.document(clinicId, documentId)
            .map(StoredDocument::metadata)
            .filter(doc -> payerId.equals(doc.payerId()) && doc.quarantineStatus() == QuarantineStatus.CLEAN)
            .isPresent();
    }

    public boolean isCleanClaimDocument(UUID clinicId, UUID claimId, UUID documentId) {
        if (clinicId == null || claimId == null || documentId == null) return false;
        return store.document(clinicId, documentId)
            .map(StoredDocument::metadata)
            .filter(doc -> claimId.equals(doc.claimId()) && doc.quarantineStatus() == QuarantineStatus.CLEAN)
            .isPresent();
    }

    public boolean evidenceExistsInClinic(UUID clinicId, UUID evidenceVersionId) {
        if (clinicId == null || evidenceVersionId == null) return false;
        return store.evidenceVersion(clinicId, evidenceVersionId).isPresent();
    }

    public DocumentMetadata uploadDocument(Actor actor, UUID claimId, UUID payerId,
                                           String originalFilename, String declaredMimeType,
                                           byte[] bytes) {
        DocumentDomain.validateParentXor(claimId, payerId);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            requireParentWriteAccess(current, claimId, payerId);
            ValidatedUpload validated = DocumentDomain.validateUpload(
                claimId, payerId, originalFilename, declaredMimeType, bytes, DocumentDomain.MAX_DOCUMENT_BYTES
            );
            UUID documentId = UUID.randomUUID();
            String quarantineKey = storage.writeQuarantine(documentId, bytes);
            DocumentScanner.ScanOutcome scan;
            try {
                scan = scanner.scan(bytes);
            } catch (RuntimeException error) {
                storage.deleteQuietly(quarantineKey);
                throw error;
            }
            if (scan.status() == ScanStatus.MALICIOUS_DETECTED) {
                storage.deleteQuietly(quarantineKey);
                throw new Failure(422, "MALICIOUS_DOCUMENT_REJECTED");
            }

            final String finalStorageKey;
            final QuarantineStatus quarantineStatus;
            final String auditAction;
            if (scan.status() == ScanStatus.CLEAN) {
                finalStorageKey = storage.promoteToClean(quarantineKey, documentId);
                quarantineStatus = QuarantineStatus.CLEAN;
                auditAction = "DOCUMENT_UPLOADED";
            } else {
                finalStorageKey = quarantineKey;
                quarantineStatus = QuarantineStatus.QUARANTINED;
                auditAction = "DOCUMENT_QUARANTINED";
            }

            Instant now = clock.instant();
            DocumentMetadata metadata = new DocumentMetadata(
                documentId,
                current.clinicId(),
                claimId,
                payerId,
                validated.sourceName(),
                validated.mimeType(),
                validated.byteCount(),
                validated.sha256Hash(),
                quarantineStatus,
                scan.status(),
                scan.detail(),
                validated.pageCount(),
                validated.imageWidth(),
                validated.imageHeight(),
                0L,
                current.userId(),
                now,
                now
            );
            store.createDocument(new StoredDocument(metadata, finalStorageKey));
            audit.record(current.userId(), current.clinicId(), auditAction, documentId);
            return metadata;
        });
    }

    public DocumentMetadata retryScan(Actor actor, UUID documentId, long expectedVersion) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            StoredDocument stored = store.lockDocument(current.clinicId(), documentId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            DocumentMetadata doc = stored.metadata();
            requireParentWriteAccess(current, doc.claimId(), doc.payerId());
            if (doc.version() != expectedVersion) {
                throw new Failure(412, "STALE_VERSION");
            }
            if (doc.quarantineStatus() != QuarantineStatus.QUARANTINED) {
                throw new Failure(409, "DOCUMENT_NOT_QUARANTINED");
            }
            byte[] bytes = storage.read(stored.storageKey());
            DocumentScanner.ScanOutcome scan = scanner.scan(bytes);
            Instant now = clock.instant();
            String nextKey = stored.storageKey();
            QuarantineStatus nextQuarantine = QuarantineStatus.QUARANTINED;
            String auditAction = "DOCUMENT_SCAN_RETRIED";

            if (scan.status() == ScanStatus.CLEAN) {
                nextKey = storage.promoteToClean(stored.storageKey(), documentId);
                nextQuarantine = QuarantineStatus.CLEAN;
                auditAction = "DOCUMENT_SCAN_RELEASED";
            } else if (scan.status() == ScanStatus.MALICIOUS_DETECTED) {
                storage.deleteQuietly(stored.storageKey());
                nextQuarantine = QuarantineStatus.REJECTED;
                auditAction = "DOCUMENT_REJECTED";
            }

            store.updateScanState(
                current.clinicId(), documentId, nextKey,
                nextQuarantine, scan.status(), scan.detail(), now, expectedVersion
            );
            audit.record(current.userId(), current.clinicId(), auditAction, documentId);
            return new DocumentMetadata(
                doc.id(), doc.clinicId(), doc.claimId(), doc.payerId(),
                doc.sourceName(), doc.mimeType(), doc.byteCount(), doc.sha256Hash(),
                nextQuarantine, scan.status(), scan.detail(),
                doc.pageCount(), doc.imageWidth(), doc.imageHeight(),
                expectedVersion + 1, doc.uploadedBy(), doc.createdAt(), now
            );
        });
    }

    public DocumentMetadata document(Actor actor, UUID documentId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            StoredDocument stored = store.document(current.clinicId(), documentId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            DocumentMetadata doc = stored.metadata();
            requireParentReadAccess(current, doc.claimId(), doc.payerId());
            return doc;
        });
    }

    public DocumentDownload download(Actor actor, UUID documentId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            StoredDocument stored = store.document(current.clinicId(), documentId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            DocumentMetadata doc = stored.metadata();
            requireParentReadAccess(current, doc.claimId(), doc.payerId());
            if (doc.quarantineStatus() != QuarantineStatus.CLEAN) {
                throw new Failure(409, "DOCUMENT_QUARANTINED");
            }
            byte[] bytes = storage.read(stored.storageKey());
            audit.record(current.userId(), current.clinicId(), "DOCUMENT_DOWNLOADED", documentId);
            return new DocumentDownload(
                doc,
                bytes,
                DocumentDomain.safeDownloadFilename(doc.id(), doc.mimeType())
            );
        });
    }

    public Page<DocumentMetadata> claimDocuments(Actor actor, UUID claimId, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            requireParentReadAccess(current, claimId, null);
            var result = store.claimDocuments(current.clinicId(), claimId, page);
            return new Page<>(result.items(), page, 25, result.totalElements());
        });
    }

    public Page<DocumentMetadata> payerDocuments(Actor actor, UUID payerId, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            requireParentReadAccess(current, null, payerId);
            var result = store.payerDocuments(current.clinicId(), payerId, page);
            return new Page<>(result.items(), page, 25, result.totalElements());
        });
    }

    public List<EvidenceVersion> evidenceVersions(Actor actor, UUID documentId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            StoredDocument stored = store.document(current.clinicId(), documentId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            requireParentReadAccess(current, stored.metadata().claimId(), stored.metadata().payerId());
            return store.evidenceVersions(current.clinicId(), documentId);
        });
    }

    public List<EvidenceVersion> extractEvidence(Actor actor, UUID documentId) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            StoredDocument stored = store.lockDocument(current.clinicId(), documentId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            DocumentMetadata doc = stored.metadata();
            requireParentWriteAccess(current, doc.claimId(), doc.payerId());
            if (doc.quarantineStatus() != QuarantineStatus.CLEAN) {
                throw new Failure(409, "DOCUMENT_QUARANTINED");
            }
            List<EvidenceVersion> existing = store.evidenceVersions(current.clinicId(), documentId);
            if (!existing.isEmpty()) {
                throw new Failure(409, "EXTRACTION_ALREADY_COMPLETED");
            }
            byte[] bytes = storage.read(stored.storageKey());
            List<ExtractedSegment> segments = DocumentDomain.extractInitialSegments(
                doc.mimeType(), bytes, doc.pageCount()
            );
            int nextVersion = store.nextEvidenceVersionNumber(current.clinicId(), documentId);
            Instant now = clock.instant();
            List<EvidenceVersion> created = new ArrayList<>(segments.size());
            for (ExtractedSegment segment : segments) {
                EvidenceVersion ev = new EvidenceVersion(
                    UUID.randomUUID(),
                    current.clinicId(),
                    documentId,
                    nextVersion++,
                    segment.pageNumber(),
                    segment.regionLabel(),
                    segment.extractedText(),
                    segment.extractionMethod(),
                    segment.qualityFlag(),
                    false,
                    null,
                    null,
                    current.userId(),
                    now
                );
                store.createEvidenceVersion(ev);
                created.add(ev);
            }
            audit.record(current.userId(), current.clinicId(), "DOCUMENT_EXTRACTED", documentId);
            return List.copyOf(created);
        });
    }

    public EvidenceVersion recordCorrection(Actor actor, UUID evidenceId, EvidenceCorrectionInput input) {
        if (input == null) throw new Failure(422, "INVALID_EVIDENCE_CORRECTION");
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            EvidenceVersion prior = store.evidenceVersion(current.clinicId(), evidenceId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            StoredDocument stored = store.lockDocument(current.clinicId(), prior.documentId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            DocumentMetadata doc = stored.metadata();
            requireParentWriteAccess(current, doc.claimId(), doc.payerId());
            if (doc.quarantineStatus() != QuarantineStatus.CLEAN) {
                throw new Failure(409, "DOCUMENT_QUARANTINED");
            }
            if (store.isSuperseded(current.clinicId(), prior.id())) {
                throw new Failure(409, "EVIDENCE_VERSION_SUPERSEDED");
            }
            ValidatedCorrection validated = DocumentDomain.validateCorrection(
                input.extractedText(), input.regionLabel(), input.correctionReason(), prior.regionLabel()
            );
            int nextVersion = store.nextEvidenceVersionNumber(current.clinicId(), doc.id());
            Instant now = clock.instant();
            EvidenceVersion corrected = new EvidenceVersion(
                UUID.randomUUID(),
                current.clinicId(),
                doc.id(),
                nextVersion,
                prior.pageNumber(),
                validated.regionLabel(),
                validated.extractedText(),
                "HUMAN_CORRECTION",
                QualityFlag.HUMAN_VERIFIED,
                true,
                validated.correctionReason(),
                prior.id(),
                current.userId(),
                now
            );
            store.createEvidenceVersion(corrected);
            audit.record(current.userId(), current.clinicId(), "EVIDENCE_CORRECTED", corrected.id());
            return corrected;
        });
    }

    private void requireParentWriteAccess(Actor current, UUID claimId, UUID payerId) {
        if (claimId != null) {
            current.requireClaimWrite();
            if (!parents.claimExists(current.clinicId(), claimId)) {
                throw new Failure(404, "NOT_FOUND");
            }
        } else {
            current.requirePayerManage();
            if (!parents.payerExists(current.clinicId(), payerId)) {
                throw new Failure(404, "NOT_FOUND");
            }
        }
    }

    private void requireParentReadAccess(Actor current, UUID claimId, UUID payerId) {
        if (claimId != null) {
            current.requireClaimRead();
            if (!parents.claimExists(current.clinicId(), claimId)) {
                throw new Failure(404, "NOT_FOUND");
            }
        } else {
            current.requirePayerRead();
            if (!parents.payerExists(current.clinicId(), payerId)) {
                throw new Failure(404, "NOT_FOUND");
            }
        }
    }

    private static void validatePage(int page) {
        if (page < 0 || page > 10000) throw new Failure(400, "INVALID_PAGE");
    }
}
