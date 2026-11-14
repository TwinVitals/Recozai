package sn.recozai.document.application;

import sn.recozai.document.api.DocumentModels.*;
import java.time.Instant;
import java.util.*;

public interface DocumentStore {
    void createDocument(StoredDocument document);
    Optional<StoredDocument> document(UUID clinicId, UUID documentId);
    Optional<StoredDocument> lockDocument(UUID clinicId, UUID documentId);
    void updateScanState(UUID clinicId, UUID documentId, String storageKey,
                         QuarantineStatus quarantineStatus, ScanStatus scanStatus,
                         String scanDetail, Instant updatedAt, long expectedVersion);
    PageResult<DocumentMetadata> claimDocuments(UUID clinicId, UUID claimId, int page);
    PageResult<DocumentMetadata> payerDocuments(UUID clinicId, UUID payerId, int page);

    List<EvidenceVersion> evidenceVersions(UUID clinicId, UUID documentId);
    Optional<EvidenceVersion> evidenceVersion(UUID clinicId, UUID evidenceId);
    boolean isSuperseded(UUID clinicId, UUID evidenceId);
    int nextEvidenceVersionNumber(UUID clinicId, UUID documentId);
    void createEvidenceVersion(EvidenceVersion version);

    record StoredDocument(
        DocumentMetadata metadata,
        String storageKey
    ) {}

    record PageResult<T>(List<T> items, long totalElements) {}
}
