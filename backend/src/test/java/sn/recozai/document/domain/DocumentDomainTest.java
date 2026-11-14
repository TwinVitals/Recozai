package sn.recozai.document.domain;

import org.junit.jupiter.api.Test;
import sn.recozai.document.api.DocumentModels.QualityFlag;
import sn.recozai.document.domain.DocumentDomain.ExtractedSegment;
import sn.recozai.document.domain.DocumentDomain.ValidatedUpload;
import sn.recozai.shared.Failure;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentDomainTest {
    private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void validateUploadEnforcesParentXorSignatureLimitsAndUnsafePdfContent() {
        byte[] validPdf = samplePdf("Convention IPM Dakar delai 30 jours", 1);

        Failure bothParents = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, PAYER_ID, "guide.pdf", "application/pdf", validPdf, 1024 * 1024));
        assertEquals("INVALID_DOCUMENT_PARENT", bothParents.code());

        Failure noParent = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(null, null, "guide.pdf", "application/pdf", validPdf, 1024 * 1024));
        assertEquals("INVALID_DOCUMENT_PARENT", noParent.code());

        ValidatedUpload cleanPdf = DocumentDomain.validateUpload(
            null, PAYER_ID, "dir/ convention-ipm.pdf ", "application/pdf", validPdf, 1024 * 1024
        );
        assertEquals("convention-ipm.pdf", cleanPdf.sourceName());
        assertEquals("application/pdf", cleanPdf.mimeType());
        assertEquals(1, cleanPdf.pageCount());
        assertEquals(64, cleanPdf.sha256Hash().length());

        Failure spoofed = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, null, "evil.pdf", "application/pdf",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), 1024 * 1024));
        assertEquals("DOCUMENT_SIGNATURE_MISMATCH", spoofed.code());

        Failure oversized = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, null, "guide.pdf", "application/pdf", validPdf, 10));
        assertEquals(413, oversized.status());
        assertEquals("DOCUMENT_TOO_LARGE", oversized.code());

        byte[] unsafeJsPdf = ("%PDF-1.4\n1 0 obj << /Type /Page /AA << /O << /S /JavaScript /JS (app.alert(1)) >> >> >> endobj\n%%EOF")
            .getBytes(StandardCharsets.ISO_8859_1);
        Failure unsafe = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, null, "script.pdf", "application/pdf", unsafeJsPdf, 1024 * 1024));
        assertEquals("UNSAFE_DOCUMENT_CONTENT", unsafe.code());

        byte[] tooManyPagesPdf = samplePdf("Page text", 101);
        Failure pageLimit = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, null, "huge.pdf", "application/pdf", tooManyPagesPdf, 1024 * 1024));
        assertEquals("DOCUMENT_PAGE_LIMIT_EXCEEDED", pageLimit.code());

        byte[] validPng = samplePng(400, 300);
        ValidatedUpload cleanPng = DocumentDomain.validateUpload(
            CLAIM_ID, null, "bordereau.png", "image/png", validPng, 1024 * 1024
        );
        assertEquals(400, cleanPng.imageWidth());
        assertEquals(300, cleanPng.imageHeight());

        byte[] hugePng = samplePng(6000, 5000); // 30 MP > 25 MP limit
        Failure pixelLimit = assertThrows(Failure.class, () ->
            DocumentDomain.validateUpload(CLAIM_ID, null, "bomb.png", "image/png", hugePng, 1024 * 1024));
        assertEquals("IMAGE_DIMENSION_LIMIT_EXCEEDED", pixelLimit.code());
    }

    @Test
    void extractInitialSegmentsAndValidateCorrectionsPreserveProvenanceRules() {
        byte[] textPdf = samplePdf("Rejet motif code IPM-04 piece justificative manquante", 1);
        List<ExtractedSegment> extracted = DocumentDomain.extractInitialSegments("application/pdf", textPdf, 1);
        assertEquals(1, extracted.size());
        assertEquals(QualityFlag.EXTRACTED, extracted.getFirst().qualityFlag());
        assertTrue(extracted.getFirst().extractedText().contains("IPM-04"));

        byte[] png = samplePng(100, 100);
        List<ExtractedSegment> imageSegments = DocumentDomain.extractInitialSegments("image/png", png, null);
        assertEquals(QualityFlag.NEEDS_OCR_OR_REVIEW, imageSegments.getFirst().qualityFlag());

        var corr = DocumentDomain.validateCorrection(
            "Rejet confirmé code IPM-04 : bulletin d'admission manquant",
            null,
            "Correction manuelle après lecture du bordereau",
            "PAGE_1"
        );
        assertEquals("PAGE_1", corr.regionLabel());

        Failure missingReason = assertThrows(Failure.class, () ->
            DocumentDomain.validateCorrection("Texte corrigé", "PAGE_1", "  ", "PAGE_1"));
        assertEquals("CORRECTION_REASON_REQUIRED", missingReason.code());
    }

    private static byte[] samplePdf(String text, int pages) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        for (int i = 1; i <= pages; i++) {
            sb.append(i).append(" 0 obj << /Type /Page >> endobj\n");
        }
        if (text != null && !text.isEmpty()) {
            sb.append("stream\nBT (").append(text).append(") Tj ET\nendstream\n");
        }
        sb.append("%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] samplePng(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.put(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        buf.putInt(13);
        buf.put("IHDR".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(width);
        buf.putInt(height);
        buf.put(new byte[] {8, 2, 0, 0, 0, 0, 0, 0});
        return buf.array();
    }
}
