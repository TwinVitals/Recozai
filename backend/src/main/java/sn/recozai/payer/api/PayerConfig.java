package sn.recozai.payer.api;

import java.time.*;
import java.util.*;

public final class PayerConfig {
    private PayerConfig() {}

    public enum RuleState { DRAFT, APPROVED, SUPERSEDED }
    public enum RuleKind { TERMINOLOGY_MAPPING, REQUIRED_DOCUMENTS, DEADLINE_RULE, WORKFLOW_STEPS }
    public enum DeadlineBasis { NOT_APPLICABLE, UNKNOWN, PARAMETERIZED_DRAFT, APPROVED_SOURCED }
    public enum VerificationStatus { UNVERIFIED_DRAFT, SOURCE_VERIFIED }

    public record Payer(UUID id, UUID clinicId, String code, String typeCode,
                        String originalName, boolean active, long version) {}

    public record DenialCategory(UUID id, UUID clinicId, String key, String labelFr,
                                 String labelEn, boolean active, long version) {}

    public record TerminologyMapping(String sourceCode, String sourceTerm, String categoryKey) {}

    public record DeadlineSpec(String trigger, Integer offsetValue, String unit,
                               String dayBasis, String timezone, String calendarSource) {}

    public record RuleConfig(List<TerminologyMapping> terminologyMappings,
                             List<String> requiredDocumentKeys,
                             DeadlineSpec deadline,
                             List<String> workflowSteps) {
        public RuleConfig {
            terminologyMappings = terminologyMappings == null ? List.of() : List.copyOf(terminologyMappings);
            requiredDocumentKeys = requiredDocumentKeys == null ? List.of() : List.copyOf(requiredDocumentKeys);
            workflowSteps = workflowSteps == null ? List.of() : List.copyOf(workflowSteps);
        }
    }

    public record RuleVersion(UUID id, UUID clinicId, UUID payerId, int versionNumber,
                              RuleState state, RuleKind ruleKind, UUID sourceDocumentId,
                              String sourceLocator, VerificationStatus verificationStatus,
                              UUID approvedBy, Instant approvedAt,
                              LocalDate effectiveFrom, LocalDate effectiveTo,
                              DeadlineBasis deadlineBasis, boolean canDriveDueDate,
                              boolean approvalAvailable, RuleConfig config,
                              UUID createdBy, Instant createdAt) {
        public RuleVersion(UUID id, UUID clinicId, UUID payerId, int versionNumber,
                           RuleState state, RuleKind ruleKind, UUID sourceDocumentId,
                           UUID approvedBy, Instant approvedAt,
                           LocalDate effectiveFrom, LocalDate effectiveTo,
                           DeadlineBasis deadlineBasis, boolean canDriveDueDate,
                           boolean approvalAvailable, RuleConfig config,
                           UUID createdBy, Instant createdAt) {
            this(
                id, clinicId, payerId, versionNumber, state, ruleKind, sourceDocumentId,
                state == RuleState.DRAFT ? null : "DOCUMENT_ROOT",
                state == RuleState.DRAFT ? VerificationStatus.UNVERIFIED_DRAFT : VerificationStatus.SOURCE_VERIFIED,
                approvedBy, approvedAt, effectiveFrom, effectiveTo, deadlineBasis,
                canDriveDueDate, approvalAvailable, config, createdBy, createdAt
            );
        }
    }
}
