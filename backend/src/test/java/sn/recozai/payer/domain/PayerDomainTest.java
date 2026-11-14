package sn.recozai.payer.domain;

import org.junit.jupiter.api.Test;
import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.shared.Failure;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PayerDomainTest {
    @Test
    void normalizesPayerAndCategoryCodesWhilePreservingOriginalSourceText() {
        assertEquals("IPM_DAKAR-01", PayerDomain.normalizePayerCode(" ipm_dakar-01 "));
        assertEquals("MUTUELLE_SANTE", PayerDomain.normalizeTypeCode(" mutuelle_sante "));
        assertEquals("Mutuelle Générale de Santé — Dakar", PayerDomain.validateOriginalName("  Mutuelle Générale de Santé — Dakar  "));
        assertEquals("missing-prior-agreement", PayerDomain.normalizeCategoryKey(" Missing-Prior-Agreement "));
        assertThrows(Failure.class, () -> PayerDomain.normalizePayerCode("x"));
        assertThrows(Failure.class, () -> PayerDomain.normalizeTypeCode("invalid space"));
        assertThrows(Failure.class, () -> PayerDomain.validateOriginalName("   "));
        assertThrows(Failure.class, () -> PayerDomain.normalizeCategoryKey("bad key!"));
    }

    @Test
    void preservesOriginalTerminologyAndRequiresActiveCategoryKeys() {
        var mapping = new TerminologyMapping("REJ-042/bis", "Pièce justificative absente (bordereau)", "Missing-Attachment");
        var validated = PayerDomain.validateRule(
            RuleKind.TERMINOLOGY_MAPPING,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            new RuleConfig(List.of(mapping), List.of(), null, List.of()),
            Set.of("missing-attachment")
        );
        var saved = validated.config().terminologyMappings().getFirst();
        assertEquals("REJ-042/bis", saved.sourceCode());
        assertEquals("Pièce justificative absente (bordereau)", saved.sourceTerm());
        assertEquals("missing-attachment", saved.categoryKey());
        assertEquals(DeadlineBasis.NOT_APPLICABLE, validated.deadlineBasis());

        Failure unknownCategory = assertThrows(Failure.class, () -> PayerDomain.validateRule(
            RuleKind.TERMINOLOGY_MAPPING, null, null,
            new RuleConfig(List.of(mapping), List.of(), null, List.of()),
            Set.of("other-category")
        ));
        assertEquals("UNKNOWN_DENIAL_CATEGORY", unknownCategory.code());
    }

    @Test
    void keepsUnknownDeadlineExplicitAndOnlyApprovedSourcedRuleCanDriveDueDate() {
        var partialDeadline = new DeadlineSpec("DENIAL_RECEIPT", null, "DAYS", "CALENDAR", "Africa/Dakar", null);
        var partialValidated = PayerDomain.validateRule(
            RuleKind.DEADLINE_RULE, null, null,
            new RuleConfig(List.of(), List.of(), partialDeadline, List.of()),
            Set.of()
        );
        assertEquals(DeadlineBasis.UNKNOWN, partialValidated.deadlineBasis());

        var completeDeadline = new DeadlineSpec("DENIAL_RECEIPT", 15, "DAYS", "CALENDAR", "Africa/Dakar", "clinic-calendar-sn");
        var completeDraftValidated = PayerDomain.validateRule(
            RuleKind.DEADLINE_RULE, null, null,
            new RuleConfig(List.of(), List.of(), completeDeadline, List.of()),
            Set.of()
        );
        assertEquals(DeadlineBasis.PARAMETERIZED_DRAFT, completeDraftValidated.deadlineBasis());
        assertFalse(PayerDomain.canDriveDueDate(RuleState.DRAFT, null, completeDraftValidated.deadlineBasis()));

        RuleVersion draftRule = new RuleVersion(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            RuleState.DRAFT, RuleKind.DEADLINE_RULE, null, null, null,
            null, null, completeDraftValidated.deadlineBasis(), false, false,
            completeDraftValidated.config(), UUID.randomUUID(), Instant.now()
        );
        assertEquals(Optional.empty(), PayerDomain.evaluateDueDate(draftRule, LocalDate.of(2026, 11, 1)));

        UUID sourceDocId = UUID.randomUUID();
        DeadlineBasis approvedBasis = PayerDomain.computeDeadlineBasis(
            RuleKind.DEADLINE_RULE, RuleState.APPROVED, sourceDocId, completeDeadline
        );
        assertEquals(DeadlineBasis.APPROVED_SOURCED, approvedBasis);
        assertTrue(PayerDomain.canDriveDueDate(RuleState.APPROVED, sourceDocId, approvedBasis));
        RuleVersion approvedRule = new RuleVersion(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            RuleState.APPROVED, RuleKind.DEADLINE_RULE, sourceDocId, UUID.randomUUID(), Instant.now(),
            null, null, approvedBasis, true, false,
            completeDraftValidated.config(), UUID.randomUUID(), Instant.now()
        );
        assertEquals(Optional.of(LocalDate.of(2026, 11, 16)),
            PayerDomain.evaluateDueDate(approvedRule, LocalDate.of(2026, 11, 1)));

        assertThrows(Failure.class, () -> PayerDomain.validateRule(
            RuleKind.DEADLINE_RULE, null, null,
            new RuleConfig(List.of(), List.of(), new DeadlineSpec("DENIAL_RECEIPT", -1, "DAYS", "CALENDAR", "Africa/Dakar", "cal"), List.of()),
            Set.of()
        ));
        assertThrows(Failure.class, () -> PayerDomain.validateRule(
            RuleKind.DEADLINE_RULE, null, null,
            new RuleConfig(List.of(), List.of(), new DeadlineSpec("DENIAL_RECEIPT", 10, "DAYS", "BUSINESS", "Africa/Dakar", "cal"), List.of()),
            Set.of()
        ));
    }

    @Test
    void enforcesAllowlistedWorkflowStepsAndDocumentKeys() {
        var docRule = PayerDomain.validateRule(
            RuleKind.REQUIRED_DOCUMENTS, null, null,
            new RuleConfig(List.of(), List.of("Invoice-Copy", "rejection_slip"), null, List.of()),
            Set.of()
        );
        assertEquals(List.of("invoice-copy", "rejection_slip"), docRule.config().requiredDocumentKeys());

        var stepRule = PayerDomain.validateRule(
            RuleKind.WORKFLOW_STEPS, null, null,
            new RuleConfig(List.of(), List.of(), null, List.of("VERIFY_SOURCE_WORDING", "COLLECT_SUPPORTING_DOCUMENTS")),
            Set.of()
        );
        assertEquals(2, stepRule.config().workflowSteps().size());

        assertThrows(Failure.class, () -> PayerDomain.validateRule(
            RuleKind.WORKFLOW_STEPS, null, null,
            new RuleConfig(List.of(), List.of(), null, List.of("EXECUTE_ARBITRARY_SCRIPT")),
            Set.of()
        ));
    }
}
