package sn.recozai.claim.domain;

import org.junit.jupiter.api.Test;
import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.claim.domain.ClaimDomain.*;
import sn.recozai.shared.Failure;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimDomainTest {
    @Test
    void monetaryValidationRespectsFrozenCurrencyScaleWithoutRounding() {
        assertEquals(new BigDecimal("125000"), ClaimDomain.parsePositiveAmount("125000", 0));
        assertEquals(new BigDecimal("125000"), ClaimDomain.parsePositiveAmount("125000.00", 0));
        assertEquals(new BigDecimal("1250.50"), ClaimDomain.parsePositiveAmount("1250.5", 2));

        Failure scaleError = assertThrows(Failure.class,
            () -> ClaimDomain.parsePositiveAmount("125000.50", 0));
        assertEquals(422, scaleError.status());
        assertEquals("INVALID_MONETARY_SCALE", scaleError.code());

        for (String invalid : List.of("0", "-500", "1.25e5", "abc", "")) {
            Failure amountError = assertThrows(Failure.class,
                () -> ClaimDomain.parsePositiveAmount(invalid, 2));
            assertEquals(422, amountError.status());
            assertEquals("INVALID_MONETARY_AMOUNT", amountError.code());
        }
    }

    @Test
    void prepareRevisionComputesExactLineTotalAndDeterministicContentHash() {
        UUID payerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<ValidatedLine> lines = ClaimDomain.validateLines(List.of(
            new ClaimLineInput("CONS-GEN", "Consultation médecine générale", "15000"),
            new ClaimLineInput("BIO-NFS", "Numération formule sanguine", "10000")
        ), 0, false);

        ValidatedRevision r1 = ClaimDomain.prepareRevision(
            payerId, "FAC-2026-001", "PAT-SN-0091", "XOF", 0, 1,
            LocalDate.of(2026, 2, 10), null, lines
        );
        ValidatedRevision r1Repeat = ClaimDomain.prepareRevision(
            payerId, "FAC-2026-001", "PAT-SN-0091", "XOF", 0, 1,
            LocalDate.of(2026, 2, 10), null, lines
        );
        assertEquals(new BigDecimal("25000"), r1.totalAmount());
        assertEquals(r1.contentHash(), r1Repeat.contentHash());
        assertEquals(64, r1.contentHash().length());

        Failure missingReason = assertThrows(Failure.class, () -> ClaimDomain.prepareRevision(
            payerId, "FAC-2026-001", "PAT-SN-0091", "XOF", 0, 2,
            LocalDate.of(2026, 2, 10), "   ", lines
        ));
        assertEquals("CHANGE_REASON_REQUIRED", missingReason.code());
    }

    @Test
    void deriveSubmissionSummaryDistinguishesMetadataCorrectionFromResubmission() {
        UUID clinicId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID rev1 = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-02-15T10:00:00Z");

        DerivedSubmissionSummary initial = ClaimDomain.deriveRevisionSubmissionSummary(rev1, List.of());
        assertEquals(RevisionSubmissionState.UNSUBMITTED, initial.state());
        assertEquals(0, initial.externalSubmissionCount());

        UUID s1Id = UUID.randomUUID();
        StoredSubmissionEvent s1 = new StoredSubmissionEvent(
            s1Id, clinicId, claimId, rev1, 1, SubmissionEventKind.SUBMISSION,
            "BORDEREAU_PAPIER", "BORD-001", LocalDate.of(2026, 2, 15), null,
            OccurrencePrecision.DATE, null, null, null, null, "k1", "h1", userId, t1
        );
        UUID s2Id = UUID.randomUUID();
        StoredSubmissionEvent s2Correction = new StoredSubmissionEvent(
            s2Id, clinicId, claimId, rev1, 1, SubmissionEventKind.METADATA_CORRECTION,
            "PORTAIL_IPM", "BORD-001-CORR", LocalDate.of(2026, 2, 16), null,
            OccurrencePrecision.DATE, null, null, s1Id, "Erreur de saisie référence bordereau",
            "k2", "h2", userId, t1.plusSeconds(60)
        );

        DerivedSubmissionSummary afterCorrection =
            ClaimDomain.deriveRevisionSubmissionSummary(rev1, List.of(s1, s2Correction));
        assertEquals(RevisionSubmissionState.SUBMITTED, afterCorrection.state());
        assertEquals(1, afterCorrection.externalSubmissionCount());
        assertEquals("PORTAIL_IPM", afterCorrection.effectiveChannelCode());
        assertEquals("BORD-001-CORR", afterCorrection.effectiveExternalSubmissionRef());
        assertEquals(LocalDate.of(2026, 2, 16), afterCorrection.effectiveOccurredOn());

        StoredSubmissionEvent s3Resub = new StoredSubmissionEvent(
            UUID.randomUUID(), clinicId, claimId, rev1, 1, SubmissionEventKind.RESUBMISSION,
            "PORTAIL_IPM", "BORD-002-RESUB", LocalDate.of(2026, 2, 20), t1.plusSeconds(120),
            OccurrencePrecision.INSTANT, null, s2Id, null, "Renvoi suite demande IPM",
            "k3", "h3", userId, t1.plusSeconds(120)
        );
        DerivedSubmissionSummary afterResub =
            ClaimDomain.deriveRevisionSubmissionSummary(rev1, List.of(s1, s2Correction, s3Resub));
        assertEquals(2, afterResub.externalSubmissionCount());
        assertEquals("BORD-002-RESUB", afterResub.effectiveExternalSubmissionRef());
        assertEquals(OccurrencePrecision.INSTANT, afterResub.effectiveOccurrencePrecision());
    }
}
