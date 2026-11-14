package sn.recozai.clinic.adapter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sn.recozai.clinic.api.ClinicSettings;
import sn.recozai.clinic.application.ClinicService;
import sn.recozai.access.Actor;
import sn.recozai.web.Versions;

@RestController
@RequestMapping("/api/v1/clinic-settings")
public class ClinicController {
    private final ClinicService service;
    public ClinicController(ClinicService service) { this.service=service; }
    public record Settings(@NotBlank @Size(max=120) String name, @NotBlank String locale,
        @NotBlank @Size(max=80) String timezone, @NotBlank @Size(min=3,max=3) String currency, @NotNull @Min(0) @Max(6) Integer fractionDigits) {}
    @GetMapping public ResponseEntity<ClinicSettings> get(@AuthenticationPrincipal Actor actor) {
        ClinicSettings settings = service.get(actor);
        return ResponseEntity.ok().eTag(Long.toString(settings.version())).body(settings);
    }
    @PatchMapping public ClinicSettings update(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Settings body,
        @RequestHeader(value="If-Match",required=false) String version) {
        return service.update(actor, body.name(), body.locale(), body.timezone(), body.currency(), body.fractionDigits(), Versions.parse(version));
    }
}
