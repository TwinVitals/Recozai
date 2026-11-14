package sn.recozai.recovery.domain;

import org.junit.jupiter.api.Test;
import sn.recozai.recovery.api.RecoveryModels.*;
import sn.recozai.recovery.domain.RecoveryDomain.*;
import sn.recozai.shared.Failure;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryDomainTest {
    @Test
    void enforcesMonetaryPrecisionForXofWithoutFloatingPointOrImplicitRounding() {
        assertEquals(new BigDecimal("125000"), RecoveryDomain.parsePositiveAmount("125000", 0));
        assertEquals(new BigDecimal("125000"), RecoveryDomain.parsePositiveAmount("125000.00", 0));
        assertEquals("125000", RecoveryDomain.formatAmount(new BigDecimal("125000.000000"), 0));

        Failure scaleError = assertThrows(Failure.class,
            () -> RecoveryDomain.parsePositiveAmount("125000.5", 0));
        assertEquals(422, scaleError.status());
        assertEquals("INVALID_MONETARY_SCALE", scaleError.code());

        for (String invalid : List.of("0", "-100", "1.5e4", "NaN", "")) {
            Failure amountError = assertThrows(Failure.class,
                () -> RecoveryDomain.parsePositiveAmount(invalid, 0));
            assertEquals(422, amountError.status());
            assertEquals("INVALID_MONETARY_AMOUNT", amountError.code());
        }
    }

    @Test
    void derivesClaimPaymentStatusAndEnforcesLedgerConservationAndReversalInvariants() {
        UUID clinicId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID revId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        BigDecimal submitted = new BigDecimal("100000");

        DerivedLedgerBalance initial = RecoveryDomain.deriveLedgerBalance(submitted, 0, List.of());
        assertEquals(ClaimPaymentStatus.UNPAID_PENDING, initial.paymentStatus());
        assertEquals(new BigDecimal("100000"), initial.outstandingAmount());

        UUID pay1Id = UUID.randomUUID();
        StoredLedgerEntry pay1 = new StoredLedgerEntry(
            pay1Id, clinicId, claimId, revId,
            LedgerEntryType.INITIAL_PAYMENT,
            new BigDecimal("60000"), new BigDecimal("100000"), new BigDecimal("40000"),
            "XOF", 0, LocalDate.of(2026, 3, 1), "VIR-001", null, null,
            "Paiement partiel bordereau IPM", "k1", "h1", userId, Instant.now()
        );
        DerivedLedgerBalance afterPartial = RecoveryDomain.deriveLedgerBalance(submitted, 0, List.of(pay1));
        assertEquals(ClaimPaymentStatus.UNPAID_PENDING, afterPartial.paymentStatus());
        assertEquals(new BigDecimal("60000"), afterPartial.initialPaidAmount());
        assertEquals(new BigDecimal("40000"), afterPartial.outstandingAmount());

        // Overpayment beyond outstanding balance is rejected
        Failure overpay = assertThrows(Failure.class, () -> RecoveryDomain.validateLedgerEntry(
            submitted, 0, List.of(pay1), LedgerEntryType.RECOVERY_PAYMENT,
            "50000", LocalDate.of(2026, 3, 2), "VIR-002", null, "Trop-perçu"
        ));
        assertEquals("LEDGER_AMOUNT_EXCEEDS_AVAILABLE", overpay.code());

        // Valid reversal restores outstanding balance
        ValidatedLedgerTransition revMutation = RecoveryDomain.validateLedgerEntry(
            submitted, 0, List.of(pay1), LedgerEntryType.REVERSAL,
            "60000", LocalDate.of(2026, 3, 2), "REV-001", pay1Id, "Annulation erreur de saisie"
        );
        assertEquals(new BigDecimal("40000"), revMutation.previousOutstandingAmount());
        assertEquals(new BigDecimal("100000"), revMutation.resultingOutstandingAmount());
        assertEquals(ClaimPaymentStatus.UNPAID_PENDING, revMutation.resultingBalance().paymentStatus());
    }

    @Test
    void evaluatesPayerRuleSnapshotsForCategoryAndSourcedDueDateOrUnknown() {
        UUID rule1 = UUID.randomUUID();
        UUID rule2 = UUID.randomUUID();
        PayerRuleSnapshot mappingAndDocs = new PayerRuleSnapshot(
            rule1,
            Map.of("REJ-042", "missing-attachment"),
            List.of("invoice-copy", "medical-certificate"),
            null, null, false,
            List.of("VERIFY_SOURCE_WORDING", "COLLECT_SUPPORTING_DOCUMENTS")
        );
        PayerRuleSnapshot sourcedDeadline = new PayerRuleSnapshot(
            rule2,
            Map.of(),
            List.of(),
            "DENIAL_RECEIPT", 15, true,
            List.of()
        );

        ResolvedCategory cat = RecoveryDomain.resolveCategory(
            "REJ-042", null, List.of(mappingAndDocs, sourcedDeadline), Set.of("missing-attachment")
        );
        assertEquals("missing-attachment", cat.categoryKey());
        assertEquals(CategoryMappingSource.APPROVED_PAYER_RULE, cat.source());
        assertEquals(rule1, cat.mappingRuleVersionId());

        ResolvedDueDate due = RecoveryDomain.resolveInitialDueDate(
            LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 10),
            List.of(mappingAndDocs, sourcedDeadline), null, null
        );
        assertEquals(DueDateBasis.APPROVED_SOURCED, due.basis());
        assertEquals(LocalDate.of(2026, 3, 25), due.dueDate());
        assertEquals(rule2, due.ruleVersionId());

        // Without an approved sourced deadline rule, due date remains explicitly UNKNOWN
        ResolvedDueDate unknownDueDate = RecoveryDomain.resolveInitialDueDate(
            LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 10),
            List.of(mappingAndDocs), null, null
        );
        assertEquals(DueDateBasis.UNKNOWN, unknownDueDate.basis());
        assertNull(unknownDueDate.dueDate());
        assertNull(unknownDueDate.ruleVersionId());
    }

    @Test
    void detectsUnresolvedPlaceholdersAndEnforcesCaseTransitions() {
        assertEquals(1, RecoveryDomain.countUnresolvedPlaceholders("Objet", "Veuillez joindre {{missing_doc}} svp", "Base"));
        assertEquals(1, RecoveryDomain.countUnresolvedPlaceholders("Objet", "Motif : [PLACEHOLDER] à compléter", "Base"));
        assertEquals(1, RecoveryDomain.countUnresolvedPlaceholders("Objet", "Montant : [TODO] XOF", "Base"));
        assertEquals(0, RecoveryDomain.countUnresolvedPlaceholders(
            "Objet : Recours FAC-2026-001",
            "Madame, Monsieur, veuillez trouver ci-joint le duplicata certifié conforme de la facture FAC-2026-001.",
            "Facture soumise le 01/02/2026."
        ));

        RecoveryDomain.validateCaseTransition(
            DenialCaseStatus.NEW, DenialCaseStatus.ACTION_REQUIRED,
            RecoveryOutcome.PENDING, null, false, false
        );

        Failure appealNotReady = assertThrows(Failure.class, () -> RecoveryDomain.validateCaseTransition(
            DenialCaseStatus.AWAITING_APPROVAL, DenialCaseStatus.APPROVED_FOR_SUBMISSION,
            RecoveryOutcome.PENDING, null, false, false
        ));
        assertEquals("APPROVED_APPEAL_REQUIRED", appealNotReady.code());

        Failure missingOutcome = assertThrows(Failure.class, () -> RecoveryDomain.validateCaseTransition(
            DenialCaseStatus.PAYER_REVIEW, DenialCaseStatus.CLOSED,
            RecoveryOutcome.PENDING, "Clôture dossier", true, true
        ));
        assertEquals("TERMINAL_RECOVERY_OUTCOME_REQUIRED", missingOutcome.code());
    }
}
