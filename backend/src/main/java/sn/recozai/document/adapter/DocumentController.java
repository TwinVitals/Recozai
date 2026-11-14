package sn.recozai.document.adapter;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sn.recozai.access.Actor;
import sn.recozai.document.api.DocumentModels.*;
import sn.recozai.document.application.DocumentService;
import sn.recozai.shared.*;
import sn.recozai.web.Versions;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentMetadata> uploadMultipart(
        @AuthenticationPrincipal Actor actor,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "claimId", required = false) UUID claimId,
        @RequestParam(value = "payerId", required = false) UUID payerId
    ) {
        if (file == null) throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new Failure(422, "INVALID_DOCUMENT_FORMAT");
        }
        DocumentMetadata created = documents.uploadDocument(
            actor,
            claimId,
            payerId,
            file.getOriginalFilename(),
            file.getContentType(),
            bytes
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"" + created.version() + "\"")
            .body(created);
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentMetadata> document(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id
    ) {
        DocumentMetadata doc = documents.document(actor, id);
        return ResponseEntity.ok()
            .eTag("\"" + doc.version() + "\"")
            .body(doc);
    }

    @GetMapping("/documents/{id}/content")
    public ResponseEntity<byte[]> download(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id
    ) {
        DocumentDownload download = documents.download(actor, id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.safeDownloadName() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType(download.metadata().mimeType()))
            .contentLength(download.bytes().length)
            .body(download.bytes());
    }

    @PostMapping("/documents/{id}/scan-retries")
    public ResponseEntity<DocumentMetadata> retryScan(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        DocumentMetadata updated = documents.retryScan(actor, id, Versions.parse(ifMatch));
        return ResponseEntity.ok()
            .eTag("\"" + updated.version() + "\"")
            .body(updated);
    }

    @GetMapping("/claims/{id}/documents")
    public Page<DocumentMetadata> claimDocuments(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page
    ) {
        return documents.claimDocuments(actor, id, page);
    }

    @GetMapping("/payers/{id}/documents")
    public Page<DocumentMetadata> payerDocuments(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page
    ) {
        return documents.payerDocuments(actor, id, page);
    }

    @GetMapping("/documents/{id}/evidence")
    public List<EvidenceVersion> evidenceVersions(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id
    ) {
        return documents.evidenceVersions(actor, id);
    }

    @PostMapping("/documents/{id}/extractions")
    public ResponseEntity<List<EvidenceVersion>> extractEvidence(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(documents.extractEvidence(actor, id));
    }

    @PostMapping("/evidence/{id}/corrections")
    public ResponseEntity<EvidenceVersion> recordCorrection(
        @AuthenticationPrincipal Actor actor,
        @PathVariable UUID id,
        @RequestBody(required = false) EvidenceCorrectionInput body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(documents.recordCorrection(actor, id, body));
    }
}
