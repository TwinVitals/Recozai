package sn.recozai.payer.adapter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sn.recozai.access.Actor;
import sn.recozai.payer.api.PayerConfig.*;
import sn.recozai.payer.application.PayerService;
import sn.recozai.shared.Page;
import sn.recozai.web.Versions;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/v1")
public class PayerController {
    private final PayerService service;

    public PayerController(PayerService service) {
        this.service = service;
    }

    public record PayerCreate(@NotBlank @Size(max = 60) String code,
                              @NotBlank @Size(max = 60) String typeCode,
                              @NotBlank @Size(max = 160) String originalName) {}

    public record PayerUpdate(@NotBlank @Size(max = 60) String code,
                              @NotBlank @Size(max = 60) String typeCode,
                              @NotBlank @Size(max = 160) String originalName,
                              @NotNull Boolean active) {}

    public record CategoryCreate(@NotBlank @Size(max = 80) String key,
                                 @NotBlank @Size(max = 160) String labelFr,
                                 @NotBlank @Size(max = 160) String labelEn) {}

    public record CategoryUpdate(@Size(max = 80) String key,
                                 @NotBlank @Size(max = 160) String labelFr,
                                 @NotBlank @Size(max = 160) String labelEn,
                                 @NotNull Boolean active) {}

    public record RuleDraftCreate(@NotNull RuleKind ruleKind,
                                  LocalDate effectiveFrom,
                                  LocalDate effectiveTo,
                                  @Size(max = 160) String sourceLocator,
                                  @NotNull RuleConfig config) {}

    public record RuleApprovalRequest(UUID sourceDocumentId,
                                      @Size(max = 160) String sourceLocator) {}

    @GetMapping("/payers")
    public Page<Payer> listPayers(@AuthenticationPrincipal Actor actor,
                                  @RequestParam(defaultValue = "0") int page) {
        return service.payers(actor, page);
    }

    @PostMapping("/payers")
    public ResponseEntity<Payer> createPayer(@AuthenticationPrincipal Actor actor,
                                             @Valid @RequestBody PayerCreate body) {
        Payer created = service.createPayer(actor, body.code(), body.typeCode(), body.originalName());
        return ResponseEntity.created(URI.create("/api/v1/payers/" + created.id()))
            .eTag(Long.toString(created.version()))
            .body(created);
    }

    @GetMapping("/payers/{id}")
    public ResponseEntity<Payer> getPayer(@AuthenticationPrincipal Actor actor,
                                          @PathVariable UUID id) {
        Payer payer = service.payer(actor, id);
        return ResponseEntity.ok().eTag(Long.toString(payer.version())).body(payer);
    }

    @PatchMapping("/payers/{id}")
    public ResponseEntity<Payer> updatePayer(@AuthenticationPrincipal Actor actor,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody PayerUpdate body,
                                             @RequestHeader(value = "If-Match", required = false) String version) {
        Payer updated = service.updatePayer(actor, id, body.code(), body.typeCode(),
            body.originalName(), body.active(), Versions.parse(version));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @GetMapping("/denial-categories")
    public Page<DenialCategory> listCategories(@AuthenticationPrincipal Actor actor,
                                               @RequestParam(defaultValue = "0") int page) {
        return service.categories(actor, page);
    }

    @PostMapping("/denial-categories")
    public ResponseEntity<DenialCategory> createCategory(@AuthenticationPrincipal Actor actor,
                                                         @Valid @RequestBody CategoryCreate body) {
        DenialCategory created = service.createCategory(actor, body.key(), body.labelFr(), body.labelEn());
        return ResponseEntity.created(URI.create("/api/v1/denial-categories/" + created.id()))
            .eTag(Long.toString(created.version()))
            .body(created);
    }

    @PatchMapping("/denial-categories/{id}")
    public ResponseEntity<DenialCategory> updateCategory(@AuthenticationPrincipal Actor actor,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody CategoryUpdate body,
                                                         @RequestHeader(value = "If-Match", required = false) String version) {
        DenialCategory updated = service.updateCategory(actor, id, body.key(), body.labelFr(),
            body.labelEn(), body.active(), Versions.parse(version));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @GetMapping("/payers/{id}/rule-versions")
    public Page<RuleVersion> listRuleVersions(@AuthenticationPrincipal Actor actor,
                                              @PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page) {
        return service.ruleVersions(actor, id, page);
    }

    @PostMapping("/payers/{id}/rule-versions")
    public ResponseEntity<RuleVersion> createRuleVersion(@AuthenticationPrincipal Actor actor,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody RuleDraftCreate body) {
        RuleVersion draft = service.createDraftRule(actor, id, body.ruleKind(),
            body.effectiveFrom(), body.effectiveTo(), body.sourceLocator(), body.config());
        return ResponseEntity.created(URI.create("/api/v1/payers/" + id + "/rule-versions"))
            .body(draft);
    }

    @PostMapping("/payer-rule-versions/{id}/approval")
    public RuleVersion approveRuleVersion(@AuthenticationPrincipal Actor actor,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody(required = false) RuleApprovalRequest body) {
        return service.approveRuleVersion(actor, id,
            body == null ? null : body.sourceDocumentId(),
            body == null ? null : body.sourceLocator());
    }
}
