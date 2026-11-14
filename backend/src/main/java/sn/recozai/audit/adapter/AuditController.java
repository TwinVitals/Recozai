package sn.recozai.audit.adapter;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sn.recozai.audit.application.AuditQuery;
import sn.recozai.access.Actor;
import sn.recozai.shared.Page;

@RestController
public class AuditController {
    private final AuditQuery query;
    public AuditController(AuditQuery query) { this.query=query; }
    @GetMapping("/api/v1/audit-events")
    public Page<AuditQuery.Event> events(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") int page) {
        return query.events(actor,page);
    }
}
