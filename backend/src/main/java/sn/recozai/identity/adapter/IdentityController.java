package sn.recozai.identity.adapter;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.identity.application.IdentityService;
import sn.recozai.identity.domain.Identity.*;
import sn.recozai.shared.*;
import sn.recozai.web.Versions;
import java.util.*;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identity;
    private final RateLimits limits;
    private final CookieCsrfTokenRepository csrf;
    private final boolean secure;
    private final Transactions tx;
    private final Audit audit;
    public IdentityController(IdentityService identity, RateLimits limits, CookieCsrfTokenRepository csrf,
        @Value("${app.secure-cookies}") boolean secure, Transactions tx, Audit audit) {
        this.identity=identity; this.limits=limits; this.csrf=csrf; this.secure=secure; this.tx=tx; this.audit=audit;
    }
    public record Login(@NotBlank @Size(max=120) String login, @NotBlank @Size(max=72) String password, UUID membershipId) {}
    public record Preference(@NotBlank String locale) {}
    public record Selection(@NotNull UUID membershipId) {}
    public record MemberChange(@NotNull Boolean active, @NotEmpty Set<@NotNull Role> roles) {}
    public record Roles(@NotEmpty Set<@NotNull Role> roles) {}
    public record Invite(@NotBlank @Size(max=120) String login, @NotEmpty Set<@NotNull Role> roles) {}
    public record Token(@NotBlank @Size(max=100) String token) {}
    public record Password(@NotBlank @Size(max=72) String currentPassword, @NotBlank @Size(max=72) String newPassword) {}
    public record Reauthentication(@NotBlank @Size(max=72) String password) {}
    public record Completion(@NotBlank @Size(max=100) String token, @NotBlank @Size(max=72) String password) {}

    @GetMapping("/session/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
    @PostMapping("/session") public IdentityService.Profile login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        limits.check("login:" + body.login().strip().toLowerCase(Locale.ROOT), 10);
        IdentityService.LoginResult result;
        try { result = identity.login(body.login(), body.password(), body.membershipId()); }
        catch (Failure failure) {
            tx.execute(() -> { audit.record(null,null,"AUTHENTICATION_REJECTED",null); return null; });
            throw failure;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.token(), 28800));
        csrf.saveToken(null, request, response);
        return identity.profile(result.actor());
    }
    private String cookie(String value, int age) {
        return ResponseCookie.from("RECOZAI_SESSION", value).path("/api").httpOnly(true).secure(secure)
            .sameSite("Strict").maxAge(age).build().toString();
    }
    @DeleteMapping("/session") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Actor actor, HttpServletRequest request, HttpServletResponse response) {
        identity.logout(actor); response.addHeader(HttpHeaders.SET_COOKIE,cookie("",0)); csrf.saveToken(null,request,response);
    }
    @GetMapping("/me") public IdentityService.Profile profile(@AuthenticationPrincipal Actor actor) { return identity.profile(actor); }
    @PostMapping("/session/membership") public IdentityService.Profile select(@AuthenticationPrincipal Actor actor,
        @Valid @RequestBody Selection body, HttpServletResponse response, HttpServletRequest request) {
        var result=identity.selectMembership(actor,body.membershipId());
        response.addHeader(HttpHeaders.SET_COOKIE,cookie(result.token(),28800));
        csrf.saveToken(null,request,response);
        return identity.profile(result.actor());
    }
    @PatchMapping("/me/preferences") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void preferences(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Preference body) { identity.locale(actor,body.locale()); }
    @GetMapping("/memberships") public Page<Membership> members(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue="0") int page) { return identity.members(actor,page); }
    @PatchMapping("/memberships/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void member(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody MemberChange body,
        @RequestHeader(value="If-Match",required=false) String version) {
        identity.updateMember(actor,id,body.active(),body.roles(),Versions.parse(version));
    }
    @PutMapping("/memberships/{id}/roles") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void roles(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Roles body,
        @RequestHeader(value="If-Match",required=false) String version) {
        identity.updateRoles(actor,id,body.roles(),Versions.parse(version));
    }
    @PostMapping("/memberships/{id}/session-revocations") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { identity.revokeMember(actor,id); }
    @PostMapping("/membership-invitations") @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.InviteResult invite(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Invite body) { return identity.invite(actor,body.login(),body.roles()); }
    @PostMapping("/membership-invitations/{id}/acceptance") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Token body) { identity.accept(actor,id,body.token()); }
    @PostMapping("/me/password-changes") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Password body) { identity.changePassword(actor,body.currentPassword(),body.newPassword()); }
    @PostMapping("/me/session-revocations") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOwn(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Reauthentication body) { identity.revokeOwnSessions(actor,body.password()); }
    @PostMapping("/account-activations") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody Completion body) { identity.completeChallenge(body.token(),body.password(),"ENROLL"); }
    @PostMapping("/password-resets") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody Completion body) { identity.completeChallenge(body.token(),body.password(),"RECOVER"); }
}
