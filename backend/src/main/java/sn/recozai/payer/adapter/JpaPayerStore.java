package sn.recozai.payer.adapter;

import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.payer.application.PayerStore;
import sn.recozai.payer.domain.PayerDomain;
import sn.recozai.platform.Database;
import sn.recozai.shared.Failure;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaPayerStore implements PayerStore {
    private final Database db;
    private final ObjectMapper json;

    public JpaPayerStore(Database db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public PageResult<Payer> payers(UUID clinicId, int page) {
        List<Payer> items = db.rows(
            "SELECT * FROM payer WHERE clinic_id=:clinic ORDER BY code,id LIMIT 25 OFFSET :offset",
            "clinic", clinicId, "offset", page * 25
        ).stream().map(this::mapPayer).toList();
        long count = number(db.rows("SELECT count(*) AS total FROM payer WHERE clinic_id=:clinic", "clinic", clinicId).getFirst(), "total");
        return new PageResult<>(items, count);
    }

    @Override
    public Optional<Payer> payer(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM payer WHERE clinic_id=:clinic AND id=:id", "clinic", clinicId, "id", id)
            .stream().findFirst().map(this::mapPayer);
    }

    @Override
    public Optional<Payer> lockPayer(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM payer WHERE clinic_id=:clinic AND id=:id FOR UPDATE", "clinic", clinicId, "id", id)
            .stream().findFirst().map(this::mapPayer);
    }

    @Override
    public boolean payerCodeExists(UUID clinicId, String code, UUID excludeId) {
        if (excludeId == null) {
            return !db.rows("SELECT id FROM payer WHERE clinic_id=:clinic AND code=:code",
                "clinic", clinicId, "code", code).isEmpty();
        }
        return !db.rows("SELECT id FROM payer WHERE clinic_id=:clinic AND code=:code AND id<>:id",
            "clinic", clinicId, "code", code, "id", excludeId).isEmpty();
    }

    @Override
    public void createPayer(Payer p) {
        db.execute(
            "INSERT INTO payer(id,clinic_id,code,type_code,original_name,active,version) VALUES(:id,:clinic,:code,:type,:name,:active,:version)",
            "id", p.id(), "clinic", p.clinicId(), "code", p.code(), "type", p.typeCode(),
            "name", p.originalName(), "active", p.active(), "version", p.version()
        );
    }

    @Override
    public void updatePayer(Payer p, long expectedVersion) {
        if (db.execute(
            "UPDATE payer SET code=:code,type_code=:type,original_name=:name,active=:active,updated_at=current_timestamp,version=version+1 WHERE clinic_id=:clinic AND id=:id AND version=:version",
            "clinic", p.clinicId(), "id", p.id(), "code", p.code(), "type", p.typeCode(),
            "name", p.originalName(), "active", p.active(), "version", expectedVersion
        ) != 1) {
            throw new Failure(412, "STALE_VERSION");
        }
    }

    @Override
    public PageResult<DenialCategory> categories(UUID clinicId, int page) {
        List<DenialCategory> items = db.rows(
            "SELECT * FROM denial_category WHERE clinic_id=:clinic ORDER BY category_key,id LIMIT 25 OFFSET :offset",
            "clinic", clinicId, "offset", page * 25
        ).stream().map(this::mapCategory).toList();
        long count = number(db.rows("SELECT count(*) AS total FROM denial_category WHERE clinic_id=:clinic", "clinic", clinicId).getFirst(), "total");
        return new PageResult<>(items, count);
    }

    @Override
    public Optional<DenialCategory> category(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM denial_category WHERE clinic_id=:clinic AND id=:id", "clinic", clinicId, "id", id)
            .stream().findFirst().map(this::mapCategory);
    }

    @Override
    public boolean categoryKeyExists(UUID clinicId, String key, UUID excludeId) {
        if (excludeId == null) {
            return !db.rows("SELECT id FROM denial_category WHERE clinic_id=:clinic AND category_key=:key",
                "clinic", clinicId, "key", key).isEmpty();
        }
        return !db.rows("SELECT id FROM denial_category WHERE clinic_id=:clinic AND category_key=:key AND id<>:id",
            "clinic", clinicId, "key", key, "id", excludeId).isEmpty();
    }

    @Override
    public Set<String> activeCategoryKeys(UUID clinicId) {
        return db.rows("SELECT category_key FROM denial_category WHERE clinic_id=:clinic AND active=true", "clinic", clinicId)
            .stream().map(r -> text(r, "category_key")).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void createCategory(DenialCategory c) {
        db.execute(
            "INSERT INTO denial_category(id,clinic_id,category_key,label_fr,label_en,active,version) VALUES(:id,:clinic,:key,:fr,:en,:active,:version)",
            "id", c.id(), "clinic", c.clinicId(), "key", c.key(), "fr", c.labelFr(),
            "en", c.labelEn(), "active", c.active(), "version", c.version()
        );
    }

    @Override
    public void updateCategory(DenialCategory c, long expectedVersion) {
        if (db.execute(
            "UPDATE denial_category SET label_fr=:fr,label_en=:en,active=:active,updated_at=current_timestamp,version=version+1 WHERE clinic_id=:clinic AND id=:id AND version=:version",
            "clinic", c.clinicId(), "id", c.id(), "fr", c.labelFr(), "en", c.labelEn(),
            "active", c.active(), "version", expectedVersion
        ) != 1) {
            throw new Failure(412, "STALE_VERSION");
        }
    }

    @Override
    public PageResult<RuleVersion> ruleVersions(UUID clinicId, UUID payerId, int page) {
        List<RuleVersion> items = db.rows(
            "SELECT * FROM payer_rule_version WHERE clinic_id=:clinic AND payer_id=:payer ORDER BY version_number DESC,id LIMIT 25 OFFSET :offset",
            "clinic", clinicId, "payer", payerId, "offset", page * 25
        ).stream().map(this::mapRuleVersion).toList();
        long count = number(db.rows(
            "SELECT count(*) AS total FROM payer_rule_version WHERE clinic_id=:clinic AND payer_id=:payer",
            "clinic", clinicId, "payer", payerId
        ).getFirst(), "total");
        return new PageResult<>(items, count);
    }

    @Override
    public List<RuleVersion> approvedRules(UUID clinicId, UUID payerId) {
        return db.rows(
            "SELECT * FROM payer_rule_version WHERE clinic_id=:clinic AND payer_id=:payer AND state='APPROVED' ORDER BY version_number DESC,id",
            "clinic", clinicId, "payer", payerId
        ).stream().map(this::mapRuleVersion).toList();
    }

    @Override
    public Optional<RuleVersion> ruleVersion(UUID clinicId, UUID id) {
        return db.rows("SELECT * FROM payer_rule_version WHERE clinic_id=:clinic AND id=:id", "clinic", clinicId, "id", id)
            .stream().findFirst().map(this::mapRuleVersion);
    }

    @Override
    public int nextRuleVersionNumber(UUID clinicId, UUID payerId) {
        var row = db.rows(
            "SELECT COALESCE(MAX(version_number),0)+1 AS next_version FROM payer_rule_version WHERE clinic_id=:clinic AND payer_id=:payer",
            "clinic", clinicId, "payer", payerId
        ).getFirst();
        return (int) number(row, "next_version");
    }

    @Override
    public void createRuleVersion(RuleVersion r) {
        String configJson = json.writeValueAsString(r.config());
        db.execute(
            "INSERT INTO payer_rule_version(id,clinic_id,payer_id,version_number,state,rule_kind,source_document_id,source_locator,verification_status,approved_by,approved_at,effective_from,effective_to,deadline_basis,config_json,created_by,created_at) " +
            "VALUES(:id,:clinic,:payer,:versionNumber,:state,:kind,CAST(:sourceDoc AS uuid),:sourceLocator,:verificationStatus,CAST(:approvedBy AS uuid),CAST(:approvedAt AS timestamptz),CAST(:effectiveFrom AS date),CAST(:effectiveTo AS date),:deadlineBasis,:configJson,:createdBy,:createdAt)",
            "id", r.id(), "clinic", r.clinicId(), "payer", r.payerId(), "versionNumber", r.versionNumber(),
            "state", r.state().name(), "kind", r.ruleKind().name(),
            "sourceDoc", r.sourceDocumentId() == null ? null : r.sourceDocumentId().toString(),
            "sourceLocator", r.sourceLocator(),
            "verificationStatus", r.verificationStatus().name(),
            "approvedBy", r.approvedBy() == null ? null : r.approvedBy().toString(),
            "approvedAt", r.approvedAt(),
            "effectiveFrom", r.effectiveFrom() == null ? null : r.effectiveFrom().toString(),
            "effectiveTo", r.effectiveTo() == null ? null : r.effectiveTo().toString(),
            "deadlineBasis", r.deadlineBasis().name(), "configJson", configJson,
            "createdBy", r.createdBy(), "createdAt", r.createdAt()
        );
    }

    @Override
    public void approveRuleVersion(RuleVersion approved) {
        db.execute(
            """
            UPDATE payer_rule_version
            SET state = 'SUPERSEDED'
            WHERE clinic_id = :clinic
              AND payer_id = :payer
              AND rule_kind = :kind
              AND state = 'APPROVED'
            """,
            "clinic", approved.clinicId(),
            "payer", approved.payerId(),
            "kind", approved.ruleKind().name()
        );
        int updated = db.execute(
            """
            UPDATE payer_rule_version
            SET state = 'APPROVED',
                source_document_id = :sourceDoc,
                source_locator = :sourceLocator,
                verification_status = :verificationStatus,
                approved_by = :approvedBy,
                approved_at = :approvedAt,
                deadline_basis = :deadlineBasis
            WHERE clinic_id = :clinic
              AND id = :id
              AND state = 'DRAFT'
            """,
            "sourceDoc", approved.sourceDocumentId(),
            "sourceLocator", approved.sourceLocator(),
            "verificationStatus", approved.verificationStatus().name(),
            "approvedBy", approved.approvedBy(),
            "approvedAt", approved.approvedAt(),
            "deadlineBasis", approved.deadlineBasis().name(),
            "clinic", approved.clinicId(),
            "id", approved.id()
        );
        if (updated != 1) {
            throw new Failure(409, "RULE_VERSION_NOT_DRAFT");
        }
    }

    private Payer mapPayer(Tuple row) {
        return new Payer(uuid(row, "id"), uuid(row, "clinic_id"), text(row, "code"),
            text(row, "type_code"), text(row, "original_name"), flag(row, "active"), number(row, "version"));
    }

    private DenialCategory mapCategory(Tuple row) {
        return new DenialCategory(uuid(row, "id"), uuid(row, "clinic_id"), text(row, "category_key"),
            text(row, "label_fr"), text(row, "label_en"), flag(row, "active"), number(row, "version"));
    }

    private RuleVersion mapRuleVersion(Tuple row) {
        RuleState state = RuleState.valueOf(text(row, "state"));
        RuleKind kind = RuleKind.valueOf(text(row, "rule_kind"));
        UUID sourceDoc = uuid(row, "source_document_id");
        String sourceLocator = text(row, "source_locator");
        VerificationStatus verificationStatus = VerificationStatus.valueOf(text(row, "verification_status"));
        DeadlineBasis basis = DeadlineBasis.valueOf(text(row, "deadline_basis"));
        RuleConfig config = json.readValue(text(row, "config_json"), RuleConfig.class);
        boolean canDriveDueDate = PayerDomain.canDriveDueDate(state, sourceDoc, basis);
        return new RuleVersion(
            uuid(row, "id"),
            uuid(row, "clinic_id"),
            uuid(row, "payer_id"),
            (int) number(row, "version_number"),
            state,
            kind,
            sourceDoc,
            sourceLocator,
            verificationStatus,
            uuid(row, "approved_by"),
            instant(row, "approved_at"),
            localDate(row, "effective_from"),
            localDate(row, "effective_to"),
            basis,
            canDriveDueDate,
            state == RuleState.DRAFT,
            config,
            uuid(row, "created_by"),
            instant(row, "created_at")
        );
    }
}
