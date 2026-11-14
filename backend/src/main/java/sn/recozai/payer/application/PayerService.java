package sn.recozai.payer.application;

import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.payer.domain.PayerDomain;
import sn.recozai.shared.*;
import java.time.*;
import java.util.*;

public final class PayerService {
    @FunctionalInterface
    public interface SourceDocumentValidator {
        boolean isCleanPayerDocument(UUID clinicId, UUID payerId, UUID documentId);
    }

    private final PayerStore store;
    private final SourceDocumentValidator documents;
    private final Transactions transactions;
    private final Access access;
    private final Audit audit;
    private final Clock clock;

    public PayerService(PayerStore store, Transactions transactions, Access access, Audit audit, Clock clock) {
        this(store, (c, p, d) -> false, transactions, access, audit, clock);
    }

    public PayerService(PayerStore store, SourceDocumentValidator documents,
                        Transactions transactions, Access access, Audit audit, Clock clock) {
        this.store = store;
        this.documents = documents;
        this.transactions = transactions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean isActivePayer(UUID clinicId, UUID payerId) {
        return store.payer(clinicId, payerId).map(Payer::active).orElse(false);
    }

    public boolean payerExists(UUID clinicId, UUID payerId) {
        return store.payer(clinicId, payerId).isPresent();
    }

    public boolean isActiveCategoryKey(UUID clinicId, String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) return false;
        return store.activeCategoryKeys(clinicId).contains(PayerDomain.normalizeCategoryKey(categoryKey));
    }

    public Set<String> activeCategoryKeys(UUID clinicId) {
        return store.activeCategoryKeys(clinicId);
    }

    public List<RuleVersion> effectiveApprovedRules(UUID clinicId, UUID payerId, LocalDate onDate) {
        return store.approvedRules(clinicId, payerId).stream()
            .filter(r -> PayerDomain.isEffectiveOn(r, onDate))
            .toList();
    }

    public Page<Payer> payers(Actor actor, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerRead();
            var result = store.payers(current.clinicId(), page);
            return new Page<>(result.items(), page, 25, result.totalElements());
        });
    }

    public Payer payer(Actor actor, UUID id) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerRead();
            return store.payer(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
        });
    }

    public Payer createPayer(Actor actor, String code, String typeCode, String originalName) {
        String normalizedCode = PayerDomain.normalizePayerCode(code);
        String normalizedType = PayerDomain.normalizeTypeCode(typeCode);
        String validatedName = PayerDomain.validateOriginalName(originalName);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            if (store.payerCodeExists(current.clinicId(), normalizedCode, null))
                throw new Failure(409, "PAYER_CODE_ALREADY_EXISTS");
            Payer created = new Payer(UUID.randomUUID(), current.clinicId(), normalizedCode,
                normalizedType, validatedName, true, 0);
            store.createPayer(created);
            audit.record(current.userId(), current.clinicId(), "PAYER_CREATED", created.id());
            return created;
        });
    }

    public Payer updatePayer(Actor actor, UUID id, String code, String typeCode,
                             String originalName, boolean active, long version) {
        String normalizedCode = PayerDomain.normalizePayerCode(code);
        String normalizedType = PayerDomain.normalizeTypeCode(typeCode);
        String validatedName = PayerDomain.validateOriginalName(originalName);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            store.payer(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (store.payerCodeExists(current.clinicId(), normalizedCode, id))
                throw new Failure(409, "PAYER_CODE_ALREADY_EXISTS");
            Payer updated = new Payer(id, current.clinicId(), normalizedCode,
                normalizedType, validatedName, active, version + 1);
            store.updatePayer(updated, version);
            audit.record(current.userId(), current.clinicId(), "PAYER_UPDATED", id);
            return updated;
        });
    }

    public Page<DenialCategory> categories(Actor actor, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerRead();
            var result = store.categories(current.clinicId(), page);
            return new Page<>(result.items(), page, 25, result.totalElements());
        });
    }

    public DenialCategory createCategory(Actor actor, String key, String labelFr, String labelEn) {
        String normalizedKey = PayerDomain.normalizeCategoryKey(key);
        String validatedFr = PayerDomain.validateCategoryLabel(labelFr);
        String validatedEn = PayerDomain.validateCategoryLabel(labelEn);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            if (store.categoryKeyExists(current.clinicId(), normalizedKey, null))
                throw new Failure(409, "CATEGORY_KEY_ALREADY_EXISTS");
            DenialCategory created = new DenialCategory(UUID.randomUUID(), current.clinicId(),
                normalizedKey, validatedFr, validatedEn, true, 0);
            store.createCategory(created);
            audit.record(current.userId(), current.clinicId(), "DENIAL_CATEGORY_CREATED", created.id());
            return created;
        });
    }

    public DenialCategory updateCategory(Actor actor, UUID id, String key, String labelFr,
                                         String labelEn, boolean active, long version) {
        String validatedFr = PayerDomain.validateCategoryLabel(labelFr);
        String validatedEn = PayerDomain.validateCategoryLabel(labelEn);
        String normalizedKey = key == null ? null : PayerDomain.normalizeCategoryKey(key);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            DenialCategory existing = store.category(current.clinicId(), id)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (normalizedKey != null && !existing.key().equals(normalizedKey))
                throw new Failure(422, "CATEGORY_KEY_IMMUTABLE");
            DenialCategory updated = new DenialCategory(id, current.clinicId(), existing.key(),
                validatedFr, validatedEn, active, version + 1);
            store.updateCategory(updated, version);
            audit.record(current.userId(), current.clinicId(), "DENIAL_CATEGORY_UPDATED", id);
            return updated;
        });
    }

    public Page<RuleVersion> ruleVersions(Actor actor, UUID payerId, int page) {
        validatePage(page);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerRead();
            store.payer(current.clinicId(), payerId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            var result = store.ruleVersions(current.clinicId(), payerId, page);
            return new Page<>(result.items(), page, 25, result.totalElements());
        });
    }

    public RuleVersion createDraftRule(Actor actor, UUID payerId, RuleKind ruleKind,
                                       LocalDate effectiveFrom, LocalDate effectiveTo,
                                       RuleConfig config) {
        return createDraftRule(actor, payerId, ruleKind, effectiveFrom, effectiveTo, null, config);
    }

    public RuleVersion createDraftRule(Actor actor, UUID payerId, RuleKind ruleKind,
                                       LocalDate effectiveFrom, LocalDate effectiveTo,
                                       String sourceLocator, RuleConfig config) {
        String normalizedLocator = PayerDomain.normalizeSourceLocator(sourceLocator, false);
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            store.lockPayer(current.clinicId(), payerId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            Set<String> activeCategories = store.activeCategoryKeys(current.clinicId());
            var validated = PayerDomain.validateRule(ruleKind, effectiveFrom, effectiveTo, config, activeCategories);
            int nextVersion = store.nextRuleVersionNumber(current.clinicId(), payerId);
            Instant now = clock.instant();
            RuleVersion draft = new RuleVersion(
                UUID.randomUUID(),
                current.clinicId(),
                payerId,
                nextVersion,
                RuleState.DRAFT,
                validated.kind(),
                null,
                normalizedLocator,
                VerificationStatus.UNVERIFIED_DRAFT,
                null,
                null,
                validated.effectiveFrom(),
                validated.effectiveTo(),
                validated.deadlineBasis(),
                false,
                true,
                validated.config(),
                current.userId(),
                now
            );
            store.createRuleVersion(draft);
            audit.record(current.userId(), current.clinicId(), "PAYER_RULE_DRAFT_CREATED", draft.id());
            return draft;
        });
    }

    public RuleVersion approveRuleVersion(Actor actor, UUID ruleVersionId) {
        return approveRuleVersion(actor, ruleVersionId, null, null);
    }

    public RuleVersion approveRuleVersion(Actor actor, UUID ruleVersionId, UUID sourceDocumentId) {
        return approveRuleVersion(actor, ruleVersionId, sourceDocumentId, null);
    }

    public RuleVersion approveRuleVersion(Actor actor, UUID ruleVersionId,
                                          UUID sourceDocumentId, String sourceLocator) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            current.requirePayerManage();
            RuleVersion existing = store.ruleVersion(current.clinicId(), ruleVersionId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            store.lockPayer(current.clinicId(), existing.payerId())
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            RuleVersion locked = store.ruleVersion(current.clinicId(), ruleVersionId)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
            if (locked.state() != RuleState.DRAFT) {
                throw new Failure(409, "RULE_VERSION_NOT_DRAFT");
            }
            if (sourceDocumentId == null) {
                throw new Failure(422, "SOURCE_DOCUMENT_REQUIRED");
            }
            if (!documents.isCleanPayerDocument(current.clinicId(), locked.payerId(), sourceDocumentId)) {
                throw new Failure(422, "INVALID_SOURCE_DOCUMENT");
            }
            String resolvedLocator = sourceLocator != null && !sourceLocator.isBlank()
                ? PayerDomain.normalizeSourceLocator(sourceLocator, true)
                : PayerDomain.normalizeSourceLocator(locked.sourceLocator(), true);
            Instant now = clock.instant();
            DeadlineBasis nextBasis = PayerDomain.computeDeadlineBasis(
                locked.ruleKind(), RuleState.APPROVED, sourceDocumentId, locked.config().deadline()
            );
            boolean canDrive = PayerDomain.canDriveDueDate(RuleState.APPROVED, sourceDocumentId, nextBasis);
            RuleVersion approved = new RuleVersion(
                locked.id(),
                locked.clinicId(),
                locked.payerId(),
                locked.versionNumber(),
                RuleState.APPROVED,
                locked.ruleKind(),
                sourceDocumentId,
                resolvedLocator,
                VerificationStatus.SOURCE_VERIFIED,
                current.userId(),
                now,
                locked.effectiveFrom(),
                locked.effectiveTo(),
                nextBasis,
                canDrive,
                false,
                locked.config(),
                locked.createdBy(),
                locked.createdAt()
            );
            store.approveRuleVersion(approved);
            audit.record(current.userId(), current.clinicId(), "PAYER_RULE_APPROVED", approved.id());
            return approved;
        });
    }

    private static void validatePage(int page) {
        if (page < 0 || page > 10000) throw new Failure(400, "INVALID_PAGE");
    }
}
