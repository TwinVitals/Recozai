package sn.recozai.document.domain;

import sn.recozai.document.api.DocumentModels.QualityFlag;
import sn.recozai.shared.Failure;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentDomain {
    private DocumentDomain() {}

    public static final long MAX_DOCUMENT_BYTES = 20L * 1024L * 1024L;
    public static final int MAX_PDF_PAGES = 100;
    public static final long MAX_IMAGE_PIXELS = 25_000_000L;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_MAGIC = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Pattern PDF_PAGE_PATTERN = Pattern.compile("/Type\\s*/Page\\b(?![sS])");
    private static final Pattern PDF_UNSAFE_PATTERN = Pattern.compile(
        "/(?:JavaScript|JS|Launch|EmbeddedFiles|RichMedia)\\b"
    );
    private static final Pattern PDF_ENCRYPT_PATTERN = Pattern.compile("/Encrypt\\b");
    private static final Pattern PDF_TEXT_LITERAL = Pattern.compile("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)\\s*(?:Tj|')");

    public record ValidatedUpload(
        String sourceName,
        String mimeType,
        long byteCount,
        String sha256Hash,
        Integer pageCount,
        Integer imageWidth,
        Integer imageHeight
    ) {}

    public record ExtractedSegment(
        int pageNumber,
        String regionLabel,
        String extractedText,
        String extractionMethod,
        QualityFlag qualityFlag
    ) {}

    public record ValidatedCorrection(
        String extractedText,
        String regionLabel,
        String correctionReason
    ) {}

    public static void validateParentXor(UUID claimId, UUID payerId) {
        if ((claimId == null) == (payerId == null)) {
            throw new Failure(422, "INVALID_DOCUMENT_PARENT");
        }
    }

    public static ValidatedUpload validateUpload(UUID claimId, UUID payerId,
                                                 String originalFilename,
                                                 String declaredMimeType,
                                                 byte[] bytes,
                                                 long maxBytes) {
        validateParentXor(claimId, payerId);
        if (bytes == null || bytes.length == 0) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        long effectiveLimit = maxBytes > 0 ? Math.min(maxBytes, MAX_DOCUMENT_BYTES) : MAX_DOCUMENT_BYTES;
        if (bytes.length > effectiveLimit) {
            throw new Failure(413, "DOCUMENT_TOO_LARGE");
        }

        String sanitizedName = sanitizeFilename(originalFilename);
        String detectedMime = detectSignatureMime(bytes);
        String normalizedDeclared = normalizeMime(declaredMimeType);
        if (normalizedDeclared != null && !normalizedDeclared.equals(detectedMime)) {
            throw new Failure(422, "DOCUMENT_SIGNATURE_MISMATCH");
        }
        validateExtensionMatchesMime(sanitizedName, detectedMime);

        Integer pageCount = null;
        Integer imageWidth = null;
        Integer imageHeight = null;

        switch (detectedMime) {
            case "application/pdf" -> pageCount = inspectPdf(bytes);
            case "image/png" -> {
                int[] dims = inspectPngDimensions(bytes);
                imageWidth = dims[0];
                imageHeight = dims[1];
            }
            case "image/jpeg" -> {
                int[] dims = inspectJpegDimensions(bytes);
                imageWidth = dims[0];
                imageHeight = dims[1];
            }
            default -> throw new Failure(422, "UNSUPPORTED_DOCUMENT_TYPE");
        }

        return new ValidatedUpload(
            sanitizedName,
            detectedMime,
            bytes.length,
            sha256Hex(bytes),
            pageCount,
            imageWidth,
            imageHeight
        );
    }

    public static List<ExtractedSegment> extractInitialSegments(String mimeType, byte[] bytes, Integer pageCount) {
        if ("application/pdf".equals(mimeType)) {
            String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
            List<String> snippets = new ArrayList<>();
            Matcher matcher = PDF_TEXT_LITERAL.matcher(ascii);
            while (matcher.find() && snippets.size() < 200) {
                String decoded = matcher.group(1)
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\n", "\n")
                    .replace("\\r", " ")
                    .strip();
                if (!decoded.isEmpty()) {
                    snippets.add(decoded);
                }
            }
            if (!snippets.isEmpty()) {
                String joined = String.join(" ", snippets);
                if (joined.length() > 4000) joined = joined.substring(0, 4000);
                return List.of(new ExtractedSegment(
                    1,
                    "PAGE_1",
                    joined,
                    "PDF_TEXT_V1",
                    QualityFlag.EXTRACTED
                ));
            }
            int pages = pageCount != null && pageCount >= 1 ? Math.min(pageCount, 5) : 1;
            List<ExtractedSegment> pending = new ArrayList<>(pages);
            for (int p = 1; p <= pages; p++) {
                pending.add(new ExtractedSegment(
                    p,
                    "PAGE_" + p,
                    "[NO_NATIVE_TEXT]",
                    "OCR_PENDING_V1",
                    QualityFlag.NEEDS_OCR_OR_REVIEW
                ));
            }
            return List.copyOf(pending);
        }
        return List.of(new ExtractedSegment(
            1,
            "IMAGE_FULL",
            "[IMAGE_EVIDENCE_PENDING_REVIEW]",
            "IMAGE_INTAKE_V1",
            QualityFlag.NEEDS_OCR_OR_REVIEW
        ));
    }

    public static ValidatedCorrection validateCorrection(String extractedText,
                                                         String regionLabel,
                                                         String correctionReason,
                                                         String fallbackRegionLabel) {
        if (extractedText == null || extractedText.isBlank()) {
            throw new Failure(422, "INVALID_EVIDENCE_CORRECTION");
        }
        String trimmedText = extractedText.strip();
        if (trimmedText.length() > 8000) {
            throw new Failure(422, "INVALID_EVIDENCE_CORRECTION");
        }
        if (correctionReason == null || correctionReason.isBlank()) {
            throw new Failure(422, "CORRECTION_REASON_REQUIRED");
        }
        String trimmedReason = correctionReason.strip();
        if (trimmedReason.length() > 240) {
            throw new Failure(422, "INVALID_EVIDENCE_CORRECTION");
        }
        String region = (regionLabel == null || regionLabel.isBlank())
            ? fallbackRegionLabel
            : regionLabel.strip();
        if (region == null || region.isEmpty() || region.length() > 120) {
            throw new Failure(422, "INVALID_EVIDENCE_CORRECTION");
        }
        return new ValidatedCorrection(trimmedText, region, trimmedReason);
    }

    public static String safeDownloadFilename(UUID documentId, String mimeType) {
        String ext = switch (mimeType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".bin";
        };
        return "document-" + documentId + ext;
    }

    private static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new Failure(422, "INVALID_DOCUMENT_FILENAME");
        }
        String normalized = originalFilename.replace('\\', '/');
        if (normalized.contains("..") || normalized.contains("\0")) {
            throw new Failure(422, "INVALID_DOCUMENT_FILENAME");
        }
        int slash = normalized.lastIndexOf('/');
        String leaf = (slash >= 0 ? normalized.substring(slash + 1) : normalized).strip();
        leaf = leaf.replaceAll("[\\p{Cntrl}]", "");
        if (leaf.isEmpty() || leaf.length() > 240) {
            throw new Failure(422, "INVALID_DOCUMENT_FILENAME");
        }
        return leaf;
    }

    private static String normalizeMime(String declaredMimeType) {
        if (declaredMimeType == null || declaredMimeType.isBlank()) return null;
        String base = declaredMimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(base)) return null;
        if (!Set.of("application/pdf", "image/png", "image/jpeg").contains(base)) {
            throw new Failure(422, "UNSUPPORTED_DOCUMENT_TYPE");
        }
        return base;
    }

    private static String detectSignatureMime(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            return "application/pdf";
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return "image/png";
        }
        if (bytes.length >= 4
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xD8
            && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new Failure(422, "DOCUMENT_SIGNATURE_MISMATCH");
    }

    private static void validateExtensionMatchesMime(String filename, String mimeType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean matches = switch (mimeType) {
            case "application/pdf" -> lower.endsWith(".pdf");
            case "image/png" -> lower.endsWith(".png");
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            default -> false;
        };
        if (!matches) {
            throw new Failure(422, "DOCUMENT_SIGNATURE_MISMATCH");
        }
    }

    private static int inspectPdf(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!content.contains("%%EOF")) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        if (PDF_ENCRYPT_PATTERN.matcher(content).find() || PDF_UNSAFE_PATTERN.matcher(content).find()) {
            throw new Failure(422, "UNSAFE_DOCUMENT_CONTENT");
        }
        Matcher pageMatcher = PDF_PAGE_PATTERN.matcher(content);
        int pages = 0;
        while (pageMatcher.find()) {
            pages++;
            if (pages > MAX_PDF_PAGES) {
                throw new Failure(422, "DOCUMENT_PAGE_LIMIT_EXCEEDED");
            }
        }
        if (pages < 1) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        return pages;
    }

    private static int[] inspectPngDimensions(byte[] bytes) {
        if (bytes.length < 24) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        int width = ByteBuffer.wrap(bytes, 16, 4).getInt();
        int height = ByteBuffer.wrap(bytes, 20, 4).getInt();
        validateDimensions(width, height);
        return new int[] { width, height };
    }

    private static int[] inspectJpegDimensions(byte[] bytes) {
        int i = 2;
        while (i + 8 < bytes.length) {
            if ((bytes[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = bytes[i + 1] & 0xFF;
            while (marker == 0xFF && i + 2 < bytes.length) {
                i++;
                marker = bytes[i + 1] & 0xFF;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                break;
            }
            if (i + 4 > bytes.length) break;
            int length = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > bytes.length) {
                throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
            }
            if (marker >= 0xC0 && marker <= 0xC3) {
                if (length < 7) throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
                int height = ((bytes[i + 5] & 0xFF) << 8) | (bytes[i + 6] & 0xFF);
                int width = ((bytes[i + 7] & 0xFF) << 8) | (bytes[i + 8] & 0xFF);
                validateDimensions(width, height);
                return new int[] { width, height };
            }
            i += 2 + length;
        }
        throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_IMAGE_PIXELS) {
            throw new Failure(422, "IMAGE_DIMENSION_LIMIT_EXCEEDED");
        }
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
