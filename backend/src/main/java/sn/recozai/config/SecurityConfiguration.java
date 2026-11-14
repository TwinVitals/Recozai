package sn.recozai.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.recozai.identity.application.IdentityService;
import sn.recozai.identity.adapter.RateLimits;
import sn.recozai.shared.Failure;
import sn.recozai.web.Problems;
import java.io.IOException;
import java.util.*;

@Configuration
@ConditionalOnWebApplication
public class SecurityConfiguration {
    @Bean CookieCsrfTokenRepository csrfRepository(@Value("${app.secure-cookies}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(secure).sameSite("Strict"));
        return repository;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, CookieCsrfTokenRepository csrf, Problems problems,
                                       FoundationFilter filter) {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/session/csrf", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/session", "/api/v1/account-activations", "/api/v1/password-resets").permitAll()
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req,res,error) -> problems.write(res,401,"AUTHENTICATION_REQUIRED"))
                .accessDeniedHandler((req,res,error) -> problems.write(res,403,"FORBIDDEN")))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .addFilterBefore(filter, CsrfFilter.class)
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable());
        return http.build();
    }
    @Bean FoundationFilter foundationFilter(IdentityService identity, RateLimits limits, Problems problems,
        @Value("${app.restore-gate}") String gate, @Value("${app.stage}") String stage) {
        if (!Set.of("development","test").contains(stage))
            throw new IllegalStateException("Phase 1 permits synthetic development/test only");
        if (!Set.of("OPEN","CLOSED").contains(gate)) throw new IllegalStateException("Invalid RESTORE_GATE");
        return new FoundationFilter(identity, limits, problems, gate);
    }
    @Bean FilterRegistrationBean<FoundationFilter> filterRegistration(FoundationFilter filter) {
        FilterRegistrationBean<FoundationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    static final class FoundationFilter extends OncePerRequestFilter {
        private final IdentityService identity;
        private final RateLimits limits;
        private final Problems problems;
        private final String gate;
        FoundationFilter(IdentityService identity, RateLimits limits, Problems problems, String gate) {
            this.identity=identity; this.limits=limits; this.problems=problems; this.gate=gate;
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
            String path = request.getRequestURI();
            response.setHeader("Cache-Control","no-store");
            if (!"OPEN".equals(gate)) { problems.write(response,503,"RESTORE_RECONCILIATION_REQUIRED"); return; }
            boolean publicAuth = path.equals("/api/v1/session/csrf") || ("POST".equals(request.getMethod())
                && Set.of("/api/v1/session","/api/v1/account-activations","/api/v1/password-resets").contains(path));
            try {
                if (publicAuth && "POST".equals(request.getMethod())) limits.check("ip:" + request.getRemoteAddr(), 50);
                if (request.getCookies() != null) {
                    for (Cookie cookie : request.getCookies()) {
                        if ("RECOZAI_SESSION".equals(cookie.getName()) && !publicAuth) {
                            identity.authenticate(cookie.getValue()).ifPresent(actor -> SecurityContextHolder.getContext()
                                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of())));
                        }
                    }
                }
            } catch (Failure failure) {
                if (failure.status() == 429) response.setHeader("Retry-After","300");
                problems.write(response, failure.status(), failure.code()); return;
            } catch (org.springframework.dao.DataAccessException | jakarta.persistence.PersistenceException
                     | org.springframework.transaction.TransactionException failure) {
                problems.databaseFailure(response); return;
            }
            chain.doFilter(request, response);
        }
    }
}
