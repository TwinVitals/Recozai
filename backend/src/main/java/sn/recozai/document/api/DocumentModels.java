package sn.recozai.document.api;

import java.time.Instant;
import java.util.UUID;

public final class DocumentModels {
    private DocumentModels() {}

    public enum QuarantineStatus {
        QUARANTINED,
        CLEAN,
        REJECTED
    }

    public enum ScanStatus {
        SCAN_PENDING,
        CLEAN,
        SCANNER_UNAVAILABLE,
        MALICIOUS_DETECTED,
        FORMAT_INVALID
    }

    public enum QualityFlag {
        EXTRACTED,
        NEEDS_OCR_OR_REVIEW,
        HUMAN_VERIFIED
    }

    public record DocumentMetadata(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID payerId,
        String sourceName,
        String mimeType,
        long byteCount,
        String sha256Hash,
        QuarantineStatus quarantineStatus,
        ScanStatus scanStatus,
        String scanDetail,
        Integer pageCount,
        Integer imageWidth,
        Integer imageHeight,
        long version,
        UUID uploadedBy,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record DocumentDownload(
        DocumentMetadata metadata,
        byte[] bytes,
        String safeDownloadName
    ) {}

    public record EvidenceVersion(
        UUID id,
        UUID clinicId,
        UUID documentId,
        int versionNumber,
        int pageNumber,
        String regionLabel,
        String extractedText,
        String extractionMethod,
        QualityFlag qualityFlag,
        boolean reviewerCorrection,
        String correctionReason,
        UUID supersedesId,
        UUID createdBy,
        Instant createdAt
    ) {}

    public record EvidenceCorrectionInput(
        String extractedText,
        String regionLabel,
        String correctionReason
    ) {}
}
