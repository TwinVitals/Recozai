package sn.recozai.claim.adapter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sn.recozai.access.Actor;
import sn.recozai.claim.api.ClaimModels.*;
import sn.recozai.claim.application.ClaimService;
import sn.recozai.claim.application.ClaimService.SubmissionCommand;
import sn.recozai.shared.Page;
import sn.recozai.web.Versions;
import java.net.URI;
import java.time.*;
import java.util.*;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/v1/claims")
public class ClaimController {
    private final ClaimService claims;

    public ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    @GetMapping
    public Page<ClaimSummary> list(@AuthenticationPrincipal Actor actor,
                                   @RequestParam(required = false) UUID payerId,
                                   @RequestParam(required = false) ClaimState state,
                                   @RequestParam(defaultValue = "0") int page) {
        return claims.claims(actor, payerId, state, page);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClaimDetail> get(@AuthenticationPrincipal Actor actor,
                                           @PathVariable UUID id) {
        ClaimDetail detail = claims.claim(actor, id);
        return ResponseEntity.ok()
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @PostMapping
    public ResponseEntity<ClaimDetail> createDraft(@AuthenticationPrincipal Actor actor,
                                                   @Valid @RequestBody DraftClaimBody body) {
        ClaimDetail detail = claims.createDraft(
            actor,
            body.payerId(),
            body.externalReference(),
            body.pseudonymousPatientRef(),
            body.sourceDate(),
            toLineInputs(body.lines())
        );
        return ResponseEntity.created(URI.create("/api/v1/claims/" + detail.id()))
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ClaimDetail> updateDraft(@AuthenticationPrincipal Actor actor,
                                                   @PathVariable UUID id,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @Valid @RequestBody DraftClaimBody body) {
        ClaimDetail detail = claims.updateDraft(
            actor,
            id,
            body.payerId(),
            body.externalReference(),
            body.pseudonymousPatientRef(),
            body.sourceDate(),
            toLineInputs(body.lines()),
            Versions.parse(ifMatch)
        );
        return ResponseEntity.ok()
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @PostMapping("/{id}/registration")
    public ResponseEntity<ClaimDetail> register(@AuthenticationPrincipal Actor actor,
                                                @PathVariable UUID id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @RequestBody(required = false) RegisterClaimBody body) {
        UUID docId = body == null ? null : body.evidenceDocumentId();
        ClaimDetail detail = claims.registerClaim(actor, id, docId, Versions.parse(ifMatch));
        return ResponseEntity.ok()
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @PostMapping("/{id}/revisions")
    public ResponseEntity<ClaimDetail> amendRevision(@AuthenticationPrincipal Actor actor,
                                                     @PathVariable UUID id,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody AmendClaimRevisionBody body) {
        ClaimDetail detail = claims.amendRegisteredClaim(
            actor,
            id,
            body.sourceDate(),
            body.changeReason(),
            toLineInputs(body.lines()),
            body.evidenceDocumentId(),
            Versions.parse(ifMatch)
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @PostMapping("/{id}/state-transitions")
    public ResponseEntity<ClaimDetail> transitionState(@AuthenticationPrincipal Actor actor,
                                                       @PathVariable UUID id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @Valid @RequestBody StateTransitionBody body) {
        ClaimDetail detail = claims.transitionState(
            actor,
            id,
            body.action(),
            body.reason(),
            Versions.parse(ifMatch)
        );
        return ResponseEntity.ok()
            .eTag(Long.toString(detail.version()))
            .body(detail);
    }

    @GetMapping("/{id}/submission-records")
    public List<ClaimSubmissionEvent> listSubmissions(@AuthenticationPrincipal Actor actor,
                                                      @PathVariable UUID id) {
        return claims.submissionRecords(actor, id);
    }

    @PostMapping("/{id}/submission-records")
    public ResponseEntity<ClaimSubmissionEvent> recordSubmission(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
        @Valid @RequestBody RecordSubmissionBody body
    ) {
        String resolvedKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader
            : body.idempotencyKey();
        ClaimSubmissionEvent event = claims.recordSubmission(
            actor,
            id,
            new SubmissionCommand(
                body.revisionId(),
                body.eventKind(),
                body.channelCode(),
                body.externalSubmissionRef(),
                body.occurredOn(),
                body.occurredAt(),
                body.occurrencePrecision(),
                body.evidenceDocumentId(),
                body.resubmissionOfId(),
                body.supersedesId(),
                body.correctionReason(),
                resolvedKey
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(event);
    }

    private static List<ClaimLineInput> toLineInputs(List<LineBody> lines) {
        if (lines == null) return List.of();
        return lines.stream()
            .map(l -> new ClaimLineInput(l.serviceCode(), l.description(), l.amount()))
            .toList();
    }

    public record LineBody(@NotBlank @Size(max = 80) String serviceCode,
                           @NotBlank @Size(max = 240) String description,
                           @NotBlank @Size(max = 40) String amount) {}

    public record DraftClaimBody(@NotNull UUID payerId,
                                 @NotBlank @Size(max = 120) String externalReference,
                                 @NotBlank @Size(max = 120) String pseudonymousPatientRef,
                                 LocalDate sourceDate,
                                 List<@Valid LineBody> lines) {}

    public record RegisterClaimBody(UUID evidenceDocumentId) {}

    public record AmendClaimRevisionBody(@NotNull LocalDate sourceDate,
                                         @NotBlank @Size(max = 240) String changeReason,
                                         @NotEmpty List<@Valid LineBody> lines,
                                         UUID evidenceDocumentId) {}

    public record StateTransitionBody(@NotNull ClaimTransitionAction action,
                                      @NotBlank @Size(max = 240) String reason) {}

    public record RecordSubmissionBody(@NotNull UUID revisionId,
                                       @NotNull SubmissionEventKind eventKind,
                                       @NotBlank @Size(max = 60) String channelCode,
                                       @NotBlank @Size(max = 120) String externalSubmissionRef,
                                       @NotNull LocalDate occurredOn,
                                       Instant occurredAt,
                                       @NotNull OccurrencePrecision occurrencePrecision,
                                       UUID evidenceDocumentId,
                                       UUID resubmissionOfId,
                                       UUID supersedesId,
                                       @Size(max = 240) String correctionReason,
                                       @Size(max = 120) String idempotencyKey) {}
}
