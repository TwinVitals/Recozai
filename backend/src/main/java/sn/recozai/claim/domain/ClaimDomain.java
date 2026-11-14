package sn.recozai.claim.domain;

import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.shared.Failure;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class ClaimDomain {
    private ClaimDomain() {}

    public record ValidatedLine(UUID id, int lineNumber, String serviceCode,
                                String description, BigDecimal submittedAmount) {}

    public record ValidatedRevision(List<ValidatedLine> lines, BigDecimal totalAmount,
                                    LocalDate sourceDate, String changeReason, String contentHash) {}

    public record StoredSubmissionEvent(UUID id, UUID clinicId, UUID claimId, UUID revisionId,
                                        int revisionNumber, SubmissionEventKind eventKind,
                                        String channelCode, String externalSubmissionRef,
                                        LocalDate occurredOn, Instant occurredAt,
                                        OccurrencePrecision occurrencePrecision,
                                        UUID evidenceDocumentId, UUID resubmissionOfId,
                                        UUID supersedesId, String correctionReason,
                                        String idempotencyKey, String requestHash,
                                        UUID recordedBy, Instant recordedAt) {}

    public record DerivedSubmissionSummary(RevisionSubmissionState state,
                                           int externalSubmissionCount,
                                           String effectiveChannelCode,
                                           String effectiveExternalSubmissionRef,
                                           LocalDate effectiveOccurredOn,
                                           Instant effectiveOccurredAt,
                                           OccurrencePrecision effectiveOccurrencePrecision) {}

    public static String validateExternalReference(String externalReference) {
        if (externalReference == null) throw new Failure(422, "INVALID_CLAIM");
        String trimmed = externalReference.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120) throw new Failure(422, "INVALID_CLAIM");
        return trimmed;
    }

    public static String validatePatientRef(String pseudonymousPatientRef) {
        if (pseudonymousPatientRef == null) throw new Failure(422, "INVALID_CLAIM");
        String trimmed = pseudonymousPatientRef.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120) throw new Failure(422, "INVALID_CLAIM");
        return trimmed;
    }

    public static String validateReason(String reason, String errorCode) {
        if (reason == null) throw new Failure(422, errorCode);
        String trimmed = reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > 240) throw new Failure(422, errorCode);
        return trimmed;
    }

    public static String normalizeChannelCode(String channelCode) {
        if (channelCode == null) throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        String normalized = channelCode.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{1,59}"))
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        return normalized;
    }

    public static String validateExternalSubmissionRef(String externalSubmissionRef) {
        if (externalSubmissionRef == null) throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        String trimmed = externalSubmissionRef.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120)
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        return trimmed;
    }

    public static String validateIdempotencyKey(String key) {
        if (key == null) throw new Failure(400, "IDEMPOTENCY_KEY_REQUIRED");
        String trimmed = key.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120)
            throw new Failure(400, "INVALID_IDEMPOTENCY_KEY");
        return trimmed;
    }

    public static void rejectDocumentUntilB08(UUID evidenceDocumentId) {
        if (evidenceDocumentId != null) {
            throw new Failure(422, "SOURCE_DOCUMENT_REQUIRED");
        }
    }

    public static BigDecimal parsePositiveAmount(String rawAmount, int currencyScale) {
        if (rawAmount == null) throw new Failure(422, "INVALID_CLAIM_LINES");
        String trimmed = rawAmount.strip();
        if (!trimmed.matches("^[0-9]+(\\.[0-9]+)?$")) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(trimmed);
        } catch (NumberFormatException error) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        int effectiveScale = Math.max(0, parsed.stripTrailingZeros().scale());
        if (effectiveScale > currencyScale) {
            throw new Failure(422, "INVALID_MONETARY_SCALE");
        }
        if (parsed.precision() - parsed.scale() > 14) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        return parsed.setScale(currencyScale, RoundingMode.UNNECESSARY);
    }

    public static String formatAmount(BigDecimal value, int currencyScale) {
        if (value == null) return null;
        return value.setScale(currencyScale, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static Money toMoney(BigDecimal value, String currency, int currencyScale) {
        if (value == null) return null;
        return new Money(formatAmount(value, currencyScale), currency);
    }

    public static List<ValidatedLine> validateLines(List<ClaimLineInput> rawLines,
                                                    int currencyScale,
                                                    boolean allowEmpty) {
        if (rawLines == null || rawLines.isEmpty()) {
            if (allowEmpty) return List.of();
            throw new Failure(422, "CLAIM_LINES_REQUIRED");
        }
        if (rawLines.size() > 200) throw new Failure(422, "INVALID_CLAIM_LINES");
        List<ValidatedLine> validated = new ArrayList<>(rawLines.size());
        int lineNumber = 1;
        for (ClaimLineInput item : rawLines) {
            if (item == null || item.serviceCode() == null || item.description() == null)
                throw new Failure(422, "INVALID_CLAIM_LINES");
            String code = item.serviceCode().strip();
            String desc = item.description().strip();
            if (code.isEmpty() || code.length() > 80 || desc.isEmpty() || desc.length() > 240)
                throw new Failure(422, "INVALID_CLAIM_LINES");
            BigDecimal amount = parsePositiveAmount(item.amount(), currencyScale);
            validated.add(new ValidatedLine(UUID.randomUUID(), lineNumber++, code, desc, amount));
        }
        return List.copyOf(validated);
    }

    public static ValidatedRevision prepareRevision(UUID payerId,
                                                    String externalReference,
                                                    String pseudonymousPatientRef,
                                                    String currency,
                                                    int currencyScale,
                                                    int revisionNumber,
                                                    LocalDate sourceDate,
                                                    String changeReason,
                                                    List<ValidatedLine> lines) {
        if (sourceDate == null) throw new Failure(422, "SOURCE_DATE_REQUIRED");
        if (lines == null || lines.isEmpty()) throw new Failure(422, "CLAIM_LINES_REQUIRED");
        String cleanedReason = null;
        if (revisionNumber > 1) {
            cleanedReason = validateReason(changeReason, "CHANGE_REASON_REQUIRED");
        } else if (changeReason != null && !changeReason.isBlank()) {
            cleanedReason = validateReason(changeReason, "INVALID_CLAIM");
        }
        BigDecimal total = BigDecimal.ZERO.setScale(currencyScale, RoundingMode.UNNECESSARY);
        for (ValidatedLine line : lines) {
            total = total.add(line.submittedAmount().setScale(currencyScale, RoundingMode.UNNECESSARY));
        }
        String hash = computeRevisionHash(
            payerId, externalReference, pseudonymousPatientRef, currency, currencyScale,
            revisionNumber, sourceDate, total, lines
        );
        return new ValidatedRevision(List.copyOf(lines), total, sourceDate, cleanedReason, hash);
    }

    public static String computeRevisionHash(UUID payerId,
                                             String externalReference,
                                             String pseudonymousPatientRef,
                                             String currency,
                                             int currencyScale,
                                             int revisionNumber,
                                             LocalDate sourceDate,
                                             BigDecimal totalAmount,
                                             List<ValidatedLine> lines) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(payerId).append('|')
            .append(externalReference).append('|')
            .append(pseudonymousPatientRef).append('|')
            .append(currency).append('|')
            .append(currencyScale).append('|')
            .append(revisionNumber).append('|')
            .append(sourceDate).append('|')
            .append(formatAmount(totalAmount, currencyScale));
        for (ValidatedLine line : lines) {
            canonical.append('|')
                .append(line.lineNumber()).append(':')
                .append(line.serviceCode()).append(':')
                .append(line.description()).append(':')
                .append(formatAmount(line.submittedAmount(), currencyScale));
        }
        return sha256Hex(canonical.toString());
    }

    public static String computeSubmissionRequestHash(UUID revisionId,
                                                      SubmissionEventKind eventKind,
                                                      String channelCode,
                                                      String externalSubmissionRef,
                                                      LocalDate occurredOn,
                                                      Instant occurredAt,
                                                      OccurrencePrecision precision,
                                                      UUID evidenceDocumentId,
                                                      UUID resubmissionOfId,
                                                      UUID supersedesId,
                                                      String correctionReason) {
        String canonical = String.join("|",
            String.valueOf(revisionId),
            String.valueOf(eventKind),
            String.valueOf(channelCode),
            String.valueOf(externalSubmissionRef),
            String.valueOf(occurredOn),
            String.valueOf(occurredAt),
            String.valueOf(precision),
            String.valueOf(evidenceDocumentId),
            String.valueOf(resubmissionOfId),
            String.valueOf(supersedesId),
            correctionReason == null ? "" : correctionReason
        );
        return sha256Hex(canonical);
    }

    public static DerivedSubmissionSummary deriveRevisionSubmissionSummary(UUID revisionId,
                                                                           List<StoredSubmissionEvent> claimEvents) {
        if (claimEvents == null || claimEvents.isEmpty()) {
            return new DerivedSubmissionSummary(
                RevisionSubmissionState.UNSUBMITTED, 0, null, null, null, null, null);
        }
        Set<UUID> supersededIds = new HashSet<>();
        for (StoredSubmissionEvent event : claimEvents) {
            if (event.supersedesId() != null) supersededIds.add(event.supersedesId());
        }
        int externalOccurrences = 0;
        StoredSubmissionEvent latestEffective = null;
        for (StoredSubmissionEvent event : claimEvents) {
            if (!revisionId.equals(event.revisionId())) continue;
            if (event.eventKind() == SubmissionEventKind.SUBMISSION
                || event.eventKind() == SubmissionEventKind.RESUBMISSION) {
                externalOccurrences++;
            }
            if (!supersededIds.contains(event.id())) {
                latestEffective = event;
            }
        }
        if (externalOccurrences == 0 || latestEffective == null) {
            return new DerivedSubmissionSummary(
                RevisionSubmissionState.UNSUBMITTED, 0, null, null, null, null, null);
        }
        return new DerivedSubmissionSummary(
            RevisionSubmissionState.SUBMITTED,
            externalOccurrences,
            latestEffective.channelCode(),
            latestEffective.externalSubmissionRef(),
            latestEffective.occurredOn(),
            latestEffective.occurredAt(),
            latestEffective.occurrencePrecision()
        );
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
