package sn.recozai.recovery.domain;

import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.shared.Failure;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecoveryDomain {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
        "\\[PLACEHOLDER[^\\]]*]|\\[TODO[^\\]]*]|\\{\\{[^}]+}}",
        Pattern.CASE_INSENSITIVE
    );

    private RecoveryDomain() {}

    public record StoredLedgerEntry(
        UUID id,
        UUID clinicId,
        UUID claimId,
        UUID revisionId,
        LedgerEntryType entryType,
        BigDecimal amount,
        BigDecimal previousOutstandingAmount,
        BigDecimal resultingOutstandingAmount,
        String currency,
        int currencyScale,
        LocalDate occurredOn,
        String payerReference,
        UUID sourceDocumentId,
        UUID reversesEntryId,
        String reason,
        String idempotencyKey,
        String requestHash,
        UUID recordedBy,
        Instant recordedAt
    ) {}

    public record DerivedLedgerBalance(
        BigDecimal submittedAmount,
        BigDecimal initialPaidAmount,
        BigDecimal contractualAdjustmentAmount,
        BigDecimal deniedDisputedAmount,
        BigDecimal recoveredAmount,
        BigDecimal writtenOffAmount,
        BigDecimal outstandingAmount,
        BigDecimal openDisputedAmount,
        ClaimPaymentStatus paymentStatus,
        Set<UUID> reversedEntryIds
    ) {}

    public record ValidatedLedgerTransition(
        BigDecimal amount,
        BigDecimal previousOutstandingAmount,
        BigDecimal resultingOutstandingAmount,
        String payerReference,
        String reason,
        DerivedLedgerBalance resultingBalance
    ) {}

    public record PayerRuleSnapshot(
        UUID ruleVersionId,
        Map<String, String> sourceCodeToCategory,
        List<String> requiredDocumentKeys,
        String deadlineTrigger,
        Integer deadlineOffsetDays,
        boolean canDriveDueDate,
        List<String> workflowSteps
    ) {}

    public record ResolvedCategory(
        String categoryKey,
        CategoryMappingSource source,
        UUID mappingRuleVersionId
    ) {}

    public record ResolvedDueDate(
        LocalDate dueDate,
        DueDateBasis basis,
        UUID ruleVersionId,
        String sourceNote
    ) {}

    public static BigDecimal parsePositiveAmount(String rawAmount, int currencyScale) {
        BigDecimal parsed = parseDecimal(rawAmount, currencyScale, "INVALID_MONETARY_AMOUNT");
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        return parsed;
    }

    public static BigDecimal parseNonNegativeAmount(String rawAmount, int currencyScale) {
        BigDecimal parsed = parseDecimal(rawAmount, currencyScale, "INVALID_MONETARY_AMOUNT");
        if (parsed.compareTo(BigDecimal.ZERO) < 0) {
            throw new Failure(422, "INVALID_MONETARY_AMOUNT");
        }
        return parsed;
    }

    private static BigDecimal parseDecimal(String rawAmount, int currencyScale, String errorCode) {
        if (rawAmount == null) throw new Failure(422, errorCode);
        String trimmed = rawAmount.strip();
        if (!trimmed.matches("^[0-9]+(\\.[0-9]+)?$")) {
            throw new Failure(422, errorCode);
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(trimmed);
        } catch (NumberFormatException error) {
            throw new Failure(422, errorCode);
        }
        int effectiveScale = Math.max(0, parsed.stripTrailingZeros().scale());
        if (effectiveScale > currencyScale) {
            throw new Failure(422, "INVALID_MONETARY_SCALE");
        }
        if (parsed.precision() - parsed.scale() > 14) {
            throw new Failure(422, errorCode);
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

    public static String validateIdempotencyKey(String key) {
        if (key == null) throw new Failure(400, "IDEMPOTENCY_KEY_REQUIRED");
        String trimmed = key.strip();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new Failure(400, "INVALID_IDEMPOTENCY_KEY");
        }
        return trimmed;
    }

    public static String validateReason(String reason, String errorCode) {
        if (reason == null) throw new Failure(422, errorCode);
        String trimmed = reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > 240) {
            throw new Failure(422, errorCode);
        }
        return trimmed;
    }

    public static String validateOptionalText(String value, int maxLength, String errorCode) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) throw new Failure(422, errorCode);
        return trimmed;
    }

    public static String validateRequiredText(String value, int maxLength, String errorCode) {
        if (value == null) throw new Failure(422, errorCode);
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new Failure(422, errorCode);
        }
        return trimmed;
    }

    public static String normalizeChannelCode(String channelCode) {
        if (channelCode == null) throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        String normalized = channelCode.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{1,59}")) {
            throw new Failure(422, "INVALID_SUBMISSION_EVENT");
        }
        return normalized;
    }

    public static DerivedLedgerBalance deriveLedgerBalance(BigDecimal submittedAmount,
                                                           int currencyScale,
                                                           List<StoredLedgerEntry> entries) {
        BigDecimal zero = BigDecimal.ZERO.setScale(currencyScale, RoundingMode.UNNECESSARY);
        BigDecimal submitted = submittedAmount.setScale(currencyScale, RoundingMode.UNNECESSARY);
        Set<UUID> reversedIds = new HashSet<>();
        if (entries != null) {
            for (StoredLedgerEntry entry : entries) {
                if (entry.entryType() == LedgerEntryType.REVERSAL && entry.reversesEntryId() != null) {
                    reversedIds.add(entry.reversesEntryId());
                }
            }
        }

        BigDecimal initialPaid = zero;
        BigDecimal contractualAdj = zero;
        BigDecimal deniedDisputed = zero;
        BigDecimal recovered = zero;
        BigDecimal writtenOff = zero;

        if (entries != null) {
            for (StoredLedgerEntry entry : entries) {
                if (entry.entryType() == LedgerEntryType.REVERSAL || reversedIds.contains(entry.id())) {
                    continue;
                }
                BigDecimal amt = entry.amount().setScale(currencyScale, RoundingMode.UNNECESSARY);
                switch (entry.entryType()) {
                    case INITIAL_PAYMENT -> initialPaid = initialPaid.add(amt);
                    case CONTRACTUAL_ADJUSTMENT -> contractualAdj = contractualAdj.add(amt);
                    case DENIAL_DISPUTED -> deniedDisputed = deniedDisputed.add(amt);
                    case RECOVERY_PAYMENT -> recovered = recovered.add(amt);
                    case WRITE_OFF -> writtenOff = writtenOff.add(amt);
                    case REVERSAL -> {}
                }
            }
        }

        BigDecimal settledTotal = initialPaid.add(contractualAdj).add(recovered).add(writtenOff);
        BigDecimal outstanding = submitted.subtract(settledTotal);
        if (outstanding.compareTo(zero) < 0) {
            outstanding = zero;
        }
        BigDecimal openDisputed = deniedDisputed.subtract(recovered).subtract(writtenOff);
        if (openDisputed.compareTo(zero) < 0) {
            openDisputed = zero;
        }

        ClaimPaymentStatus status = computePaymentStatus(
            initialPaid, deniedDisputed, recovered, writtenOff, outstanding, openDisputed, zero
        );

        return new DerivedLedgerBalance(
            submitted,
            initialPaid,
            contractualAdj,
            deniedDisputed,
            recovered,
            writtenOff,
            outstanding,
            openDisputed,
            status,
            Set.copyOf(reversedIds)
        );
    }

    private static ClaimPaymentStatus computePaymentStatus(BigDecimal initialPaid,
                                                           BigDecimal deniedDisputed,
                                                           BigDecimal recovered,
                                                           BigDecimal writtenOff,
                                                           BigDecimal outstanding,
                                                           BigDecimal openDisputed,
                                                           BigDecimal zero) {
        if (outstanding.compareTo(zero) == 0) {
            if (writtenOff.compareTo(zero) > 0 && initialPaid.compareTo(zero) == 0 && recovered.compareTo(zero) == 0) {
                return ClaimPaymentStatus.WRITTEN_OFF;
            }
            if (writtenOff.compareTo(zero) > 0 && recovered.compareTo(zero) > 0) {
                return ClaimPaymentStatus.RECOVERED_PARTIAL;
            }
            return ClaimPaymentStatus.PAID_OR_RECOVERED_FULL;
        }
        if (recovered.compareTo(zero) > 0) {
            return ClaimPaymentStatus.RECOVERED_PARTIAL;
        }
        if (openDisputed.compareTo(zero) > 0 || deniedDisputed.compareTo(zero) > 0) {
            return initialPaid.compareTo(zero) > 0
                ? ClaimPaymentStatus.PARTIALLY_PAID_DISPUTED
                : ClaimPaymentStatus.DENIED_UNPAID;
        }
        return ClaimPaymentStatus.UNPAID_PENDING;
    }

    public static ValidatedLedgerTransition validateLedgerEntry(
        BigDecimal submittedAmount,
        int currencyScale,
        List<StoredLedgerEntry> existingEntries,
        LedgerEntryType entryType,
        String rawAmount,
        LocalDate occurredOn,
        String payerReference,
        UUID reversesEntryId,
        String reason
    ) {
        if (entryType == null || occurredOn == null) {
            throw new Failure(422, "INVALID_LEDGER_ENTRY");
        }
        BigDecimal amount = parsePositiveAmount(rawAmount, currencyScale);
        String validatedReason = validateReason(reason, "INVALID_LEDGER_ENTRY");
        String validatedRef = validateOptionalText(payerReference, 120, "INVALID_LEDGER_ENTRY");

        DerivedLedgerBalance current = deriveLedgerBalance(submittedAmount, currencyScale, existingEntries);
        BigDecimal previousOutstanding = current.outstandingAmount();

        if (entryType == LedgerEntryType.REVERSAL) {
            if (reversesEntryId == null) {
                throw new Failure(422, "REVERSAL_TARGET_REQUIRED");
            }
            StoredLedgerEntry target = existingEntries.stream()
                .filter(e -> e.id().equals(reversesEntryId))
                .findFirst()
                .orElseThrow(() -> new Failure(404, "REVERSAL_TARGET_NOT_FOUND"));
            if (target.entryType() == LedgerEntryType.REVERSAL) {
                throw new Failure(422, "CANNOT_REVERSE_REVERSAL");
            }
            if (current.reversedEntryIds().contains(target.id())) {
                throw new Failure(409, "ENTRY_ALREADY_REVERSED");
            }
            if (target.amount().setScale(currencyScale, RoundingMode.UNNECESSARY).compareTo(amount) != 0) {
                throw new Failure(422, "REVERSAL_AMOUNT_MISMATCH");
            }
            List<StoredLedgerEntry> simulated = new ArrayList<>(existingEntries);
            simulated.add(new StoredLedgerEntry(
                UUID.randomUUID(), target.clinicId(), target.claimId(), target.revisionId(),
                LedgerEntryType.REVERSAL, amount, previousOutstanding, previousOutstanding,
                target.currency(), currencyScale, occurredOn, validatedRef, null,
                reversesEntryId, validatedReason, "sim", "sim", target.recordedBy(), Instant.now()
            ));
            DerivedLedgerBalance after = deriveLedgerBalance(submittedAmount, currencyScale, simulated);
            if (after.recoveredAmount().add(after.writtenOffAmount()).compareTo(after.deniedDisputedAmount()) > 0
                && after.deniedDisputedAmount().compareTo(BigDecimal.ZERO) >= 0
                && target.entryType() == LedgerEntryType.DENIAL_DISPUTED) {
                throw new Failure(409, "REVERSAL_VIOLATES_CONSERVATION");
            }
            return new ValidatedLedgerTransition(
                amount, previousOutstanding, after.outstandingAmount(),
                validatedRef, validatedReason, after
            );
        }

        if (reversesEntryId != null) {
            throw new Failure(422, "INVALID_LEDGER_ENTRY");
        }

        BigDecimal undisputedAvailable = current.outstandingAmount().subtract(current.openDisputedAmount());
        switch (entryType) {
            case INITIAL_PAYMENT, CONTRACTUAL_ADJUSTMENT, DENIAL_DISPUTED -> {
                if (amount.compareTo(undisputedAvailable) > 0) {
                    throw new Failure(409, "LEDGER_AMOUNT_EXCEEDS_AVAILABLE");
                }
            }
            case RECOVERY_PAYMENT, WRITE_OFF -> {
                if (current.openDisputedAmount().compareTo(BigDecimal.ZERO) > 0) {
                    if (amount.compareTo(current.openDisputedAmount()) > 0) {
                        throw new Failure(409, "RECOVERY_EXCEEDS_DISPUTED_AMOUNT");
                    }
                } else if (amount.compareTo(current.outstandingAmount()) > 0) {
                    throw new Failure(409, "LEDGER_AMOUNT_EXCEEDS_AVAILABLE");
                }
            }
            case REVERSAL -> throw new Failure(422, "INVALID_LEDGER_ENTRY");
        }

        List<StoredLedgerEntry> simulated = new ArrayList<>(existingEntries == null ? List.of() : existingEntries);
        simulated.add(new StoredLedgerEntry(
            UUID.randomUUID(), null, null, null, entryType, amount,
            previousOutstanding, previousOutstanding, "XOF", currencyScale,
            occurredOn, validatedRef, null, null, validatedReason, "sim", "sim", null, Instant.now()
        ));
        DerivedLedgerBalance after = deriveLedgerBalance(submittedAmount, currencyScale, simulated);
        return new ValidatedLedgerTransition(
            amount, previousOutstanding, after.outstandingAmount(),
            validatedRef, validatedReason, after
        );
    }

    public static ResolvedCategory resolveCategory(String sourceReasonCode,
                                                   String explicitCategoryKey,
                                                   List<PayerRuleSnapshot> effectiveRules,
                                                   Set<String> activeClinicCategories) {
        String cleanedSourceCode = validateOptionalText(sourceReasonCode, 80, "INVALID_DENIAL_EVENT");
        if (cleanedSourceCode != null && effectiveRules != null) {
            for (PayerRuleSnapshot rule : effectiveRules) {
                if (rule.sourceCodeToCategory() != null) {
                    String mapped = rule.sourceCodeToCategory().get(cleanedSourceCode);
                    if (mapped != null && activeClinicCategories.contains(mapped)) {
                        return new ResolvedCategory(
                            mapped,
                            CategoryMappingSource.APPROVED_PAYER_RULE,
                            rule.ruleVersionId()
                        );
                    }
                }
            }
        }
        if (explicitCategoryKey == null || explicitCategoryKey.isBlank()) {
            throw new Failure(422, "DENIAL_CATEGORY_REQUIRED");
        }
        String normalizedKey = explicitCategoryKey.strip().toLowerCase(Locale.ROOT);
        if (!activeClinicCategories.contains(normalizedKey)) {
            throw new Failure(422, "UNKNOWN_DENIAL_CATEGORY");
        }
        return new ResolvedCategory(normalizedKey, CategoryMappingSource.MANUAL_STAFF_SELECTION, null);
    }

    public static ResolvedDueDate resolveInitialDueDate(LocalDate noticeDate,
                                                        LocalDate receivedDate,
                                                        List<PayerRuleSnapshot> effectiveRules,
                                                        LocalDate staffConfirmedDueDate,
                                                        String staffDueDateNote) {
        if (staffConfirmedDueDate != null) {
            String note = validateRequiredText(staffDueDateNote, 240, "DUE_DATE_SOURCE_NOTE_REQUIRED");
            return new ResolvedDueDate(staffConfirmedDueDate, DueDateBasis.STAFF_CONFIRMED, null, note);
        }
        if (effectiveRules != null) {
            for (PayerRuleSnapshot rule : effectiveRules) {
                if (rule.canDriveDueDate() && rule.deadlineOffsetDays() != null) {
                    LocalDate baseDate = "DISCREPANCY_NOTICE".equals(rule.deadlineTrigger())
                        ? noticeDate
                        : receivedDate;
                    if (baseDate != null) {
                        return new ResolvedDueDate(
                            baseDate.plusDays(rule.deadlineOffsetDays()),
                            DueDateBasis.APPROVED_SOURCED,
                            rule.ruleVersionId(),
                            null
                        );
                    }
                }
            }
        }
        return new ResolvedDueDate(null, DueDateBasis.UNKNOWN, null, null);
    }

    public static int countUnresolvedPlaceholders(String subjectLine,
                                                  String bodyText,
                                                  String factualBasisSummary) {
        return countMatches(subjectLine) + countMatches(bodyText) + countMatches(factualBasisSummary);
    }

    private static int countMatches(String text) {
        if (text == null || text.isEmpty()) return 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public static void validateCaseTransition(DenialCaseStatus currentStatus,
                                              DenialCaseStatus targetStatus,
                                              RecoveryOutcome targetOutcome,
                                              String closureReason,
                                              boolean hasApprovedAppeal,
                                              boolean hasAppealSubmissionRecord) {
        if (targetStatus == null) throw new Failure(422, "INVALID_CASE_STATUS");
        if (currentStatus == DenialCaseStatus.CLOSED && targetStatus != DenialCaseStatus.CLOSED) {
            throw new Failure(409, "RECOVERY_CASE_ALREADY_CLOSED");
        }
        if (targetStatus == DenialCaseStatus.APPROVED_FOR_SUBMISSION && !hasApprovedAppeal) {
            throw new Failure(409, "APPROVED_APPEAL_REQUIRED");
        }
        if (targetStatus == DenialCaseStatus.SUBMITTED_TO_PAYER && !hasAppealSubmissionRecord) {
            throw new Failure(409, "APPEAL_SUBMISSION_RECORD_REQUIRED");
        }
        if (targetStatus == DenialCaseStatus.CLOSED) {
            if (targetOutcome == null || targetOutcome == RecoveryOutcome.PENDING) {
                throw new Failure(422, "TERMINAL_RECOVERY_OUTCOME_REQUIRED");
            }
            validateReason(closureReason, "CLOSURE_REASON_REQUIRED");
        } else if (targetOutcome != null && targetOutcome != RecoveryOutcome.PENDING) {
            throw new Failure(422, "NON_CLOSED_CASE_OUTCOME_MUST_BE_PENDING");
        }
    }

    public static String sha256Hex(String canonicalInput) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
