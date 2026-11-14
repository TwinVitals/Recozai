package sn.recozai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import sn.recozai.access.Access;
import sn.recozai.audit.api.Audit;
import sn.recozai.claim.api.ClaimModels.ClaimState;
import sn.recozai.claim.application.*;
import sn.recozai.clinic.application.*;
import sn.recozai.document.adapter.*;
import sn.recozai.document.application.*;
import sn.recozai.identity.application.*;
import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.payer.application.*;
import sn.recozai.recovery.application.*;
import sn.recozai.recovery.domain.RecoveryDomain.PayerRuleSnapshot;
import sn.recozai.shared.Transactions;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** Composition root: wires module ports and cross-module coordinators; holds no business rules. */
@Configuration
public class FoundationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean IdentityService identityService(IdentityStore store, Credentials crypto, Transactions tx, Audit audit,
                                          Clock clock, @Value("${app.security-epoch}") String epoch,
                                          @Value("${app.stage}") String stage) {
        requireSyntheticStage(stage, epoch);
        return new IdentityService(store, crypto, tx, audit, clock, epoch);
    }
    /** Clinic provisioning for custodian bootstrap goes through Clinic's application API, never its store. */
    @Bean CustodianService custodianService(IdentityStore store, ClinicService clinics, Credentials crypto, Transactions tx,
                                            Audit audit, Clock clock, @Value("${app.security-epoch}") String epoch,
                                            @Value("${app.stage}") String stage) {
        requireSyntheticStage(stage, epoch);
        return new CustodianService(store, clinics::provision, crypto, tx, audit, clock, epoch);
    }
    private static void requireSyntheticStage(String stage, String epoch) {
        if (!java.util.Set.of("development","test").contains(stage))
            throw new IllegalStateException("Phase 1 permits synthetic development/test only");
        if (epoch.isBlank() || epoch.length() > 120) throw new IllegalStateException("SECURITY_EPOCH required");
    }
    @Bean ClinicService clinicService(ClinicStore store, Transactions tx, Access access, Audit audit) {
        return new ClinicService(store, tx, access, audit);
    }
    @Bean PayerService payerService(PayerStore store, ObjectProvider<DocumentService> documents,
                                    Transactions tx, Access access, Audit audit, Clock clock) {
        return new PayerService(
            store,
            (clinicId, payerId, docId) -> documents.getObject().isCleanPayerDocument(clinicId, payerId, docId),
            tx,
            access,
            audit,
            clock
        );
    }
    @Bean ClaimService claimService(ClaimStore store, ClinicService clinics, PayerService payers,
                                    ObjectProvider<DocumentService> documents,
                                    Transactions tx, Access access, Audit audit, Clock clock) {
        return new ClaimService(
            store,
            clinicId -> {
                var settings = clinics.settingsForClinic(clinicId);
                return new ClaimService.CurrencyPolicy(settings.currency(), settings.fractionDigits());
            },
            payers::isActivePayer,
            (clinicId, claimId, docId) -> documents.getObject().isCleanClaimDocument(clinicId, claimId, docId),
            tx,
            access,
            audit,
            clock
        );
    }
    @Bean DocumentStorage documentStorage(
        @Value("${app.document-storage-dir:${java.io.tmpdir}/recozai-documents}") String storageDir
    ) {
        return new LocalDocumentStorage(Paths.get(storageDir));
    }
    @Bean ConfigurableDocumentScanner documentScanner() {
        return new ConfigurableDocumentScanner();
    }
    @Bean DocumentService documentService(DocumentStore store, DocumentStorage storage, DocumentScanner scanner,
                                          ClaimService claims, PayerService payers,
                                          Transactions tx, Access access, Audit audit, Clock clock) {
        return new DocumentService(
            store,
            storage,
            scanner,
            new DocumentService.ParentAccessPort() {
                @Override
                public boolean claimExists(java.util.UUID clinicId, java.util.UUID claimId) {
                    return claims.claimExists(clinicId, claimId);
                }
                @Override
                public boolean payerExists(java.util.UUID clinicId, java.util.UUID payerId) {
                    return payers.payerExists(clinicId, payerId);
                }
            },
            tx,
            access,
            audit,
            clock
        );
    }
    @Bean RecoveryService recoveryService(RecoveryStore store, ClaimService claims, PayerService payers,
                                          DocumentService documents,
                                          Transactions tx, Access access, Audit audit, Clock clock) {
        return new RecoveryService(
            store,
            new RecoveryService.ClaimPort() {
                @Override
                public Optional<RecoveryService.ActiveClaimFact> activeClaim(UUID clinicId, UUID claimId) {
                    return claims.activeClaimContext(clinicId, claimId).map(FoundationConfiguration::toClaimFact);
                }
                @Override
                public Optional<RecoveryService.ActiveClaimFact> lockActiveClaim(UUID clinicId, UUID claimId) {
                    return claims.lockActiveClaimContext(clinicId, claimId).map(FoundationConfiguration::toClaimFact);
                }
            },
            new RecoveryService.PayerPolicyPort() {
                @Override
                public Set<String> activeCategoryKeys(UUID clinicId) {
                    return payers.activeCategoryKeys(clinicId);
                }
                @Override
                public List<PayerRuleSnapshot> effectiveApprovedRules(UUID clinicId, UUID payerId, java.time.LocalDate onDate) {
                    return payers.effectiveApprovedRules(clinicId, payerId, onDate).stream()
                        .map(FoundationConfiguration::toRuleSnapshot)
                        .toList();
                }
            },
            new RecoveryService.DocumentValidationPort() {
                @Override
                public boolean isCleanClaimDocument(UUID clinicId, UUID claimId, UUID documentId) {
                    return documents.isCleanClaimDocument(clinicId, claimId, documentId);
                }
                @Override
                public boolean evidenceExistsInClinic(UUID clinicId, UUID evidenceVersionId) {
                    return documents.evidenceExistsInClinic(clinicId, evidenceVersionId);
                }
            },
            tx,
            access,
            audit,
            clock
        );
    }

    private static RecoveryService.ActiveClaimFact toClaimFact(ClaimService.ActiveClaimContext c) {
        return new RecoveryService.ActiveClaimFact(
            c.claimId(),
            c.clinicId(),
            c.payerId(),
            c.externalReference(),
            c.currency(),
            c.currencyScale(),
            c.state() == ClaimState.SUBMITTED,
            c.activeRevisionId(),
            c.activeRevisionNumber(),
            c.submittedAmount()
        );
    }

    private static PayerRuleSnapshot toRuleSnapshot(RuleVersion r) {
        Map<String, String> mappings = r.config().terminologyMappings().stream()
            .collect(Collectors.toUnmodifiableMap(
                TerminologyMapping::sourceCode,
                TerminologyMapping::categoryKey,
                (a, b) -> a
            ));
        DeadlineSpec deadline = r.config().deadline();
        Integer offsetDays = deadline != null && "CALENDAR".equals(deadline.dayBasis())
            ? deadline.offsetValue()
            : null;
        String trigger = deadline != null ? deadline.trigger() : null;
        return new PayerRuleSnapshot(
            r.id(),
            mappings,
            r.config().requiredDocumentKeys(),
            trigger,
            offsetDays,
            r.canDriveDueDate(),
            r.config().workflowSteps()
        );
    }

    @Bean sn.recozai.audit.application.AuditQuery auditQuery(sn.recozai.audit.application.AuditQuery.Store store,Access access,Transactions tx) {
        return new sn.recozai.audit.application.AuditQuery(store,access,tx);
    }
}
