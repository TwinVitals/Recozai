package sn.recozai.payer.domain;

import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.shared.Failure;
import java.time.*;
import java.util.*;

public final class PayerDomain {
    private PayerDomain() {}

    public static final Set<String> PERMITTED_WORKFLOW_STEPS = Set.of(
        "VERIFY_SOURCE_WORDING",
        "COLLECT_SUPPORTING_DOCUMENTS",
        "RECONCILE_EXPECTED_AMOUNT",
        "REQUEST_CLINICAL_CLARIFICATION",
        "PREPARE_APPEAL_DRAFT",
        "MANAGER_ESCALATION_REVIEW"
    );
    private static final Set<String> PERMITTED_TRIGGERS = Set.of(
        "CLAIM_SUBMISSION",
        "DENIAL_RECEIPT",
        "DISCREPANCY_NOTICE",
        "PAYER_INFORMATION_REQUEST"
    );
    private static final Set<String> PERMITTED_UNITS = Set.of("DAYS", "BUSINESS_DAYS");
    private static final Set<String> PERMITTED_DAY_BASES = Set.of("CALENDAR", "BUSINESS");

    public record ValidatedRule(RuleKind kind, LocalDate effectiveFrom, LocalDate effectiveTo,
                                DeadlineBasis deadlineBasis, RuleConfig config) {}

    public static String normalizePayerCode(String code) {
        if (code == null) throw new Failure(422, "INVALID_PAYER");
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{1,59}")) throw new Failure(422, "INVALID_PAYER");
        return normalized;
    }

    public static String normalizeTypeCode(String typeCode) {
        if (typeCode == null) throw new Failure(422, "INVALID_PAYER");
        String normalized = typeCode.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{1,59}")) throw new Failure(422, "INVALID_PAYER");
        return normalized;
    }

    public static String validateOriginalName(String originalName) {
        if (originalName == null) throw new Failure(422, "INVALID_PAYER");
        String trimmed = originalName.strip();
        if (trimmed.isEmpty() || trimmed.length() > 160) throw new Failure(422, "INVALID_PAYER");
        return trimmed;
    }

    public static String normalizeCategoryKey(String key) {
        if (key == null) throw new Failure(422, "INVALID_DENIAL_CATEGORY");
        String normalized = key.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{1,79}"))
            throw new Failure(422, "INVALID_DENIAL_CATEGORY");
        return normalized;
    }

    public static String validateCategoryLabel(String label) {
        if (label == null) throw new Failure(422, "INVALID_DENIAL_CATEGORY");
        String trimmed = label.strip();
        if (trimmed.isEmpty() || trimmed.length() > 160)
            throw new Failure(422, "INVALID_DENIAL_CATEGORY");
        return trimmed;
    }

    public static ValidatedRule validateRule(RuleKind kind, LocalDate effectiveFrom,
                                             LocalDate effectiveTo, RuleConfig config,
                                             Set<String> activeCategoryKeys) {
        if (kind == null || config == null) throw new Failure(422, "INVALID_RULE_CONFIG");
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom))
            throw new Failure(422, "INVALID_RULE_CONFIG");

        List<TerminologyMapping> mappings = validateMappings(kind, config.terminologyMappings(), activeCategoryKeys);
        List<String> documents = validateDocuments(kind, config.requiredDocumentKeys());
        DeadlineSpec deadline = validateDeadline(kind, config.deadline());
        List<String> steps = validateWorkflowSteps(kind, config.workflowSteps());
        DeadlineBasis basis = computeDeadlineBasis(kind, RuleState.DRAFT, null, deadline);
        return new ValidatedRule(kind, effectiveFrom, effectiveTo, basis,
            new RuleConfig(mappings, documents, deadline, steps));
    }

    public static DeadlineBasis computeDeadlineBasis(RuleKind kind, RuleState state,
                                                     UUID sourceDocumentId, DeadlineSpec deadline) {
        if (kind != RuleKind.DEADLINE_RULE) return DeadlineBasis.NOT_APPLICABLE;
        if (deadline == null
            || deadline.trigger() == null
            || deadline.offsetValue() == null
            || deadline.unit() == null
            || deadline.dayBasis() == null
            || deadline.timezone() == null
            || deadline.calendarSource() == null) {
            return DeadlineBasis.UNKNOWN;
        }
        if (state == RuleState.APPROVED && sourceDocumentId != null) {
            return DeadlineBasis.APPROVED_SOURCED;
        }
        return DeadlineBasis.PARAMETERIZED_DRAFT;
    }

    public static boolean canDriveDueDate(RuleState state, UUID sourceDocumentId, DeadlineBasis basis) {
        return state == RuleState.APPROVED && sourceDocumentId != null && basis == DeadlineBasis.APPROVED_SOURCED;
    }

    public static Optional<LocalDate> evaluateDueDate(RuleVersion rule, LocalDate triggerDate) {
        if (rule == null || triggerDate == null || !rule.canDriveDueDate()) return Optional.empty();
        DeadlineSpec deadline = rule.config().deadline();
        if (deadline == null || deadline.offsetValue() == null || !"CALENDAR".equals(deadline.dayBasis()))
            return Optional.empty();
        return Optional.of(triggerDate.plusDays(deadline.offsetValue()));
    }

    public static String normalizeSourceLocator(String rawLocator, boolean defaultWhenBlank) {
        if (rawLocator == null || rawLocator.isBlank()) {
            return defaultWhenBlank ? "DOCUMENT_ROOT" : null;
        }
        String trimmed = rawLocator.strip();
        if (trimmed.length() > 160) throw new Failure(422, "INVALID_SOURCE_LOCATOR");
        return trimmed;
    }

    public static boolean isEffectiveOn(RuleVersion rule, LocalDate onDate) {
        if (rule == null || onDate == null || rule.state() != RuleState.APPROVED) return false;
        if (rule.effectiveFrom() != null && onDate.isBefore(rule.effectiveFrom())) return false;
        if (rule.effectiveTo() != null && onDate.isAfter(rule.effectiveTo())) return false;
        return true;
    }

    private static List<TerminologyMapping> validateMappings(RuleKind kind,
                                                             List<TerminologyMapping> raw,
                                                             Set<String> activeCategoryKeys) {
        if (kind != RuleKind.TERMINOLOGY_MAPPING) {
            if (raw != null && !raw.isEmpty()) throw new Failure(422, "INVALID_RULE_CONFIG");
            return List.of();
        }
        if (raw == null || raw.isEmpty() || raw.size() > 100)
            throw new Failure(422, "INVALID_RULE_CONFIG");
        Set<String> seenCodes = new HashSet<>();
        List<TerminologyMapping> normalized = new ArrayList<>(raw.size());
        for (TerminologyMapping item : raw) {
            if (item == null || item.sourceCode() == null || item.sourceTerm() == null || item.categoryKey() == null)
                throw new Failure(422, "INVALID_RULE_CONFIG");
            String sourceCode = item.sourceCode().strip();
            String sourceTerm = item.sourceTerm().strip();
            String categoryKey = normalizeCategoryKey(item.categoryKey());
            if (sourceCode.isEmpty() || sourceCode.length() > 80
                || sourceTerm.isEmpty() || sourceTerm.length() > 240
                || !seenCodes.add(sourceCode))
                throw new Failure(422, "INVALID_RULE_CONFIG");
            if (!activeCategoryKeys.contains(categoryKey))
                throw new Failure(422, "UNKNOWN_DENIAL_CATEGORY");
            normalized.add(new TerminologyMapping(sourceCode, sourceTerm, categoryKey));
        }
        return List.copyOf(normalized);
    }

    private static List<String> validateDocuments(RuleKind kind, List<String> raw) {
        if (kind != RuleKind.REQUIRED_DOCUMENTS) {
            if (raw != null && !raw.isEmpty()) throw new Failure(422, "INVALID_RULE_CONFIG");
            return List.of();
        }
        if (raw == null || raw.isEmpty() || raw.size() > 50)
            throw new Failure(422, "INVALID_RULE_CONFIG");
        Set<String> seen = new LinkedHashSet<>();
        for (String key : raw) {
            if (key == null) throw new Failure(422, "INVALID_RULE_CONFIG");
            String normalized = key.strip().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9][a-z0-9._-]{1,79}") || !seen.add(normalized))
                throw new Failure(422, "INVALID_RULE_CONFIG");
        }
        return List.copyOf(seen);
    }

    private static DeadlineSpec validateDeadline(RuleKind kind, DeadlineSpec raw) {
        if (kind != RuleKind.DEADLINE_RULE) {
            if (raw != null) throw new Failure(422, "INVALID_RULE_CONFIG");
            return null;
        }
        if (raw == null) return new DeadlineSpec(null, null, null, null, null, null);
        String trigger = cleanNullable(raw.trigger(), true);
        if (trigger != null && !PERMITTED_TRIGGERS.contains(trigger))
            throw new Failure(422, "INVALID_RULE_CONFIG");
        Integer offset = raw.offsetValue();
        if (offset != null && (offset < 0 || offset > 3650))
            throw new Failure(422, "INVALID_RULE_CONFIG");
        String unit = cleanNullable(raw.unit(), true);
        if (unit != null && !PERMITTED_UNITS.contains(unit))
            throw new Failure(422, "INVALID_RULE_CONFIG");
        String dayBasis = cleanNullable(raw.dayBasis(), true);
        if (dayBasis != null && !PERMITTED_DAY_BASES.contains(dayBasis))
            throw new Failure(422, "INVALID_RULE_CONFIG");
        if (unit != null && dayBasis != null) {
            if (("DAYS".equals(unit) && !"CALENDAR".equals(dayBasis))
                || ("BUSINESS_DAYS".equals(unit) && !"BUSINESS".equals(dayBasis)))
                throw new Failure(422, "INVALID_RULE_CONFIG");
        }
        String timezone = cleanNullable(raw.timezone(), false);
        if (timezone != null) {
            try { ZoneId.of(timezone); }
            catch (DateTimeException error) { throw new Failure(422, "INVALID_RULE_CONFIG"); }
        }
        String calendarSource = cleanNullable(raw.calendarSource(), false);
        if (calendarSource != null) {
            calendarSource = calendarSource.toLowerCase(Locale.ROOT);
            if (!calendarSource.matches("[a-z0-9][a-z0-9._-]{1,79}"))
                throw new Failure(422, "INVALID_RULE_CONFIG");
        }
        return new DeadlineSpec(trigger, offset, unit, dayBasis, timezone, calendarSource);
    }

    private static List<String> validateWorkflowSteps(RuleKind kind, List<String> raw) {
        if (kind != RuleKind.WORKFLOW_STEPS) {
            if (raw != null && !raw.isEmpty()) throw new Failure(422, "INVALID_RULE_CONFIG");
            return List.of();
        }
        if (raw == null || raw.isEmpty() || raw.size() > 20)
            throw new Failure(422, "INVALID_RULE_CONFIG");
        Set<String> seen = new LinkedHashSet<>();
        for (String step : raw) {
            if (step == null) throw new Failure(422, "INVALID_RULE_CONFIG");
            String normalized = step.strip().toUpperCase(Locale.ROOT);
            if (!PERMITTED_WORKFLOW_STEPS.contains(normalized) || !seen.add(normalized))
                throw new Failure(422, "INVALID_RULE_CONFIG");
        }
        return List.copyOf(seen);
    }

    private static String cleanNullable(String value, boolean upper) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        return upper ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    }
}
