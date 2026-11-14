package sn.recozai.payer.application;

import sn.recozai.payer.api.PayerConfig.*;
import java.util.*;

public interface PayerStore {
    PageResult<Payer> payers(UUID clinicId, int page);
    Optional<Payer> payer(UUID clinicId, UUID id);
    Optional<Payer> lockPayer(UUID clinicId, UUID id);
    boolean payerCodeExists(UUID clinicId, String code, UUID excludeId);
    void createPayer(Payer payer);
    void updatePayer(Payer payer, long expectedVersion);

    PageResult<DenialCategory> categories(UUID clinicId, int page);
    Optional<DenialCategory> category(UUID clinicId, UUID id);
    boolean categoryKeyExists(UUID clinicId, String key, UUID excludeId);
    Set<String> activeCategoryKeys(UUID clinicId);
    void createCategory(DenialCategory category);
    void updateCategory(DenialCategory category, long expectedVersion);

    PageResult<RuleVersion> ruleVersions(UUID clinicId, UUID payerId, int page);
    List<RuleVersion> approvedRules(UUID clinicId, UUID payerId);
    Optional<RuleVersion> ruleVersion(UUID clinicId, UUID id);
    int nextRuleVersionNumber(UUID clinicId, UUID payerId);
    void createRuleVersion(RuleVersion ruleVersion);
    void approveRuleVersion(RuleVersion approved);

    record PageResult<T>(List<T> items, long totalElements) {}
}
