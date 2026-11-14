package sn.recozai.config;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import sn.recozai.access.*;
import sn.recozai.audit.api.Audit;
import sn.recozai.clinic.api.ClinicSettings;
import sn.recozai.clinic.application.ClinicStore;
import sn.recozai.identity.application.*;
import sn.recozai.identity.domain.Identity.*;
import sn.recozai.platform.Database;
import sn.recozai.shared.*;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"app.stage=test","app.restore-gate=OPEN","app.secure-cookies=true"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class FoundationIntegrationTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    static final KeyPair KEYS = keys();
    static final String PASSWORD = "SYNTHETIC-test-password-25";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("app.security-epoch",()->"test-epoch");
        registry.add("app.custodian-public-key",()->Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Transactions tx;
    @Autowired IdentityStore identities;
    @Autowired ClinicStore clinics;
    @Autowired IdentityService service;
    @Autowired Credentials crypto;
    @Autowired Audit audit;
    @Autowired Database db;
    @Autowired IdentityOperator operator;
    @Autowired SecurityConfiguration security;
    @Autowired sn.recozai.identity.adapter.RateLimits limits;
    @Autowired sn.recozai.web.Problems problems;
    @Autowired sn.recozai.document.adapter.ConfigurableDocumentScanner scanner;
    record Fixture(UUID clinic,UUID user,UUID member,String login) {}

    @BeforeEach void clearRateWindows() {
        scanner.setAvailable(true);
        tx.execute(()->db.execute("DELETE FROM auth_rate_limit"));
    }
    private Fixture fixture(Role role) {
        return tx.execute(()->{
            UUID clinic=UUID.randomUUID(), user=UUID.randomUUID();
            String login="synthetic-"+user;
            clinics.create(new ClinicSettings(clinic,"Synthetic clinic","fr-SN","Africa/Dakar","XOF",0,0));
            identities.createUser(user,login); identities.credentials(user,crypto.encode(PASSWORD),true);
            return new Fixture(clinic,user,identities.addMember(clinic,user,Set.of(role)),login);
        });
    }
    private Cookie login(Fixture fixture) throws Exception {
        var result=mvc.perform(post("/api/v1/session").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("login",fixture.login(),"password",PASSWORD,"membershipId",fixture.member()))))
            .andExpect(status().isOk()).andReturn();
        String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(v->v.startsWith("RECOZAI_SESSION=")).findFirst().orElseThrow();
        assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("Secure")); assertTrue(header.contains("SameSite=Strict"));
        return new Cookie("RECOZAI_SESSION",java.net.HttpCookie.parse(header).getFirst().getValue());
    }
    @Test void csrfAndAnonymousRequestsFailClosed() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/session").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/session/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void authenticationDatabaseOutageReturnsSafeProblemBeforeDispatch() throws Exception {
        IdentityService unavailable=org.mockito.Mockito.mock(IdentityService.class);
        org.mockito.Mockito.when(unavailable.authenticate("synthetic-session"))
            .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("synthetic-sensitive-diagnostic"));
        var request=new MockHttpServletRequest("GET","/api/v1/me");
        request.setCookies(new Cookie("RECOZAI_SESSION","synthetic-session"));
        var response=new MockHttpServletResponse();
        new SecurityConfiguration.FoundationFilter(unavailable,limits,problems,"OPEN")
            .doFilter(request,response,(req,res)->fail("Unavailable authentication must not dispatch"));
        assertEquals(503,response.getStatus());
        assertEquals("DATA_OPERATION_FAILED",json.readTree(response.getContentAsString()).get("code").asString());
        assertFalse(response.getContentAsString().contains("synthetic-sensitive-diagnostic"));
    }
    @Test void membershipAdministrationCannotCrossClinicOrChangeIdentity() throws Exception {
        Fixture a=fixture(Role.CLINIC_ADMIN), b=fixture(Role.CLINIC_ADMIN);
        Cookie cookie=login(a);
        mvc.perform(patch("/api/v1/memberships/"+b.member()).cookie(cookie).with(csrf()).header("If-Match","\"0\"")
            .contentType("application/json").content("{\"active\":false,\"roles\":[\"BILLING_STAFF\"]}"))
            .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/memberships/"+b.member()).cookie(cookie).with(csrf()).header("If-Match","\"0\"")
            .contentType("application/json").content("{\"active\":false,\"roles\":[\"BILLING_STAFF\"],\"password\":\"forbidden\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/users/"+b.user()+"/recovery-challenges").cookie(cookie).with(csrf())).andExpect(status().isNotFound());
        assertTrue(tx.execute(()->identities.user(b.user()).enabled()));
    }
    @Test void sharedUserOffboardingDoesNotRevokeOtherClinic() throws Exception {
        Fixture a=fixture(Role.CLINIC_ADMIN), user=fixture(Role.BILLING_STAFF);
        UUID memberA=tx.execute(()->identities.addMember(a.clinic(),user.user(),Set.of(Role.BILLING_STAFF)));
        Cookie sessionA=login(new Fixture(a.clinic(),user.user(),memberA,user.login()));
        Cookie sessionB=login(user), admin=login(a);
        mvc.perform(patch("/api/v1/memberships/"+memberA).cookie(admin).with(csrf()).header("If-Match","\"0\"")
            .contentType("application/json").content("{\"active\":false,\"roles\":[\"BILLING_STAFF\"]}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(sessionA)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(sessionB)).andExpect(status().isOk()).andExpect(jsonPath("$.clinicId").value(user.clinic().toString()));
        assertTrue(tx.execute(()->identities.user(user.user()).enabled()));
    }
    @Test void settingsRequireRolePreconditionAndValidTimezone() throws Exception {
        Fixture a=fixture(Role.CLINIC_ADMIN), staff=fixture(Role.BILLING_STAFF);
        Cookie admin=login(a);
        String settings="{\"name\":\"Synthetic updated\",\"locale\":\"en\",\"timezone\":\"Africa/Dakar\",\"currency\":\"XOF\",\"fractionDigits\":0}";
        mvc.perform(patch("/api/v1/clinic-settings").cookie(login(staff)).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(settings)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/clinic-settings").cookie(admin).with(csrf()).contentType("application/json").content(settings)).andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/clinic-settings").cookie(admin).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(settings)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/clinic-settings").cookie(admin).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(settings)).andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/api/v1/clinic-settings").cookie(admin).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(settings.replace("Africa/Dakar","Unknown/Zone"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/v1/clinic-settings").cookie(admin).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(settings.replace(",\"fractionDigits\":0",""))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit-events").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].action").value("CLINIC_CONFIGURED"));
    }
    @Test void localeSwitchPreservesSessionAndLogoutRevokesIt() throws Exception {
        Cookie cookie=login(fixture(Role.BILLING_STAFF));
        mvc.perform(patch("/api/v1/me/preferences").cookie(cookie).with(csrf()).contentType("application/json").content("{\"locale\":\"en\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.locale").value("en"));
        mvc.perform(delete("/api/v1/session").cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void invitationRequiresAuthenticatedIntendedOwnerAndIsSingleUse() throws Exception {
        Fixture admin=fixture(Role.CLINIC_ADMIN), owner=fixture(Role.BILLING_STAFF), stranger=fixture(Role.BILLING_STAFF);
        Actor actor=service.authenticate(login(admin).getValue()).orElseThrow();
        var invite=service.invite(actor,owner.login(),Set.of(Role.CLAIMS_REVIEWER));
        String body=json.writeValueAsString(Map.of("token",invite.token()));
        mvc.perform(post("/api/v1/membership-invitations/"+invite.id()+"/acceptance").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/membership-invitations/"+invite.id()+"/acceptance").cookie(login(stranger)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        Cookie cookie=login(owner);
        mvc.perform(post("/api/v1/membership-invitations/"+invite.id()+"/acceptance").cookie(cookie).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/membership-invitations/"+invite.id()+"/acceptance").cookie(cookie).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
    }
    @Test void custodianRecoveryRequiresSignatureAndRevokesOldSessions() throws Exception {
        Fixture owner=fixture(Role.BILLING_STAFF); Cookie cookie=login(owner);
        var grant=new CustodianService.Grant(UUID.randomUUID(),"RECOVER",owner.login(),null,"synthetic-custodian","synthetic-owner-check",Instant.now().plusSeconds(300),"test-epoch");
        String signed=sign(grant);
        assertThrows(Failure.class,()->operator.issue(signed.substring(0,signed.length()-4)+"AAAA"));
        String challenge=operator.issue(signed);
        assertThrows(Failure.class,()->operator.issue(signed));
        mvc.perform(post("/api/v1/password-resets").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token",challenge,"password","SYNTHETIC-new-password-25")))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
        assertThrows(Failure.class,()->service.completeChallenge(challenge,PASSWORD,"RECOVER"));
    }
    @Test void revokedInvitationIssuerCannotGrantMembershipLater() throws Exception {
        Fixture admin=fixture(Role.CLINIC_ADMIN), owner=fixture(Role.BILLING_STAFF);
        Actor actor=service.authenticate(login(admin).getValue()).orElseThrow();
        var invitation=service.invite(actor,owner.login(),Set.of(Role.BILLING_STAFF));
        service.revokeMember(actor,admin.member());
        Actor ownerActor=service.authenticate(login(owner).getValue()).orElseThrow();
        Failure error=assertThrows(Failure.class,()->service.accept(ownerActor,invitation.id(),invitation.token()));
        assertEquals("INVALID_INVITATION",error.code());
    }
    @Test void clinicSelectionRotatesSessionAndRejectsForeignMembership() throws Exception {
        Fixture owner=fixture(Role.BILLING_STAFF), other=fixture(Role.CLINIC_ADMIN);
        UUID second=tx.execute(()->identities.addMember(other.clinic(),owner.user(),Set.of(Role.MANAGEMENT_VIEWER)));
        Cookie old=login(owner);
        mvc.perform(post("/api/v1/session/membership").cookie(old).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("membershipId",other.member())))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/session/membership").cookie(old).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("membershipId",second)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.clinicId").value(other.clinic().toString())).andExpect(jsonPath("$.roles[0]").value("MANAGEMENT_VIEWER"));
        mvc.perform(get("/api/v1/me").cookie(old)).andExpect(status().isUnauthorized());
    }
    @Test void migrationsPreserveUnknownLegacyProvenanceWithoutInventingEvidence() throws Exception {
        var configuration=org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
            .defaultSchema("upgrade_fixture").schemas("upgrade_fixture");
        configuration.target("1").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=connection.prepareStatement("INSERT INTO upgrade_fixture.operator_authorization(nonce) VALUES(?)")) {
            statement.setObject(1,UUID.randomUUID()); statement.executeUpdate();
        }
        org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
            .defaultSchema("upgrade_fixture").schemas("upgrade_fixture").load().migrate();
        var row=tx.execute(()->db.rows("SELECT provenance_status,custodian FROM upgrade_fixture.operator_authorization").getFirst());
        assertEquals("LEGACY_UNVERIFIED",row.get("provenance_status"));
        assertNull(row.get("custodian"));
    }
    @Test void restoreEpochAndGateRejectResurrectedAccess() throws Exception {
        Cookie cookie=login(fixture(Role.BILLING_STAFF));
        var restored=new IdentityService(identities,crypto,tx,audit,Clock.systemUTC(),"fresh-restore-epoch");
        assertThrows(Failure.class,()->restored.authenticate(cookie.getValue()));
        var filter=new SecurityConfiguration().foundationFilter(service,limits,problems,"CLOSED","test");
        var request=new MockHttpServletRequest("GET","/api/v1/me");
        request.setCookies(cookie);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->fail("Restore gate was bypassed"));
        assertEquals(503,response.getStatus());
        assertTrue(response.getContentAsString().contains("RESTORE_RECONCILIATION_REQUIRED"));
    }
    @Test void auditIsImmutableAndFailureRollsBackMutation() {
        Fixture a=fixture(Role.CLINIC_ADMIN);
        assertThrows(RuntimeException.class,()->tx.execute(()->{
            identities.locale(a.user(),"en");
            audit.record(a.user(),UUID.randomUUID(),"INVALID_CLINIC_REFERENCE",a.user());
            return null;
        }));
        assertEquals("fr-SN",tx.execute(()->identities.user(a.user()).locale()));
        tx.execute(()->{audit.record(a.user(),a.clinic(),"TEST_EVENT",a.user());return null;});
        assertThrows(RuntimeException.class,()->tx.execute(()->db.execute("DELETE FROM audit_event WHERE clinic_id=:id","id",a.clinic())));
    }
    @Test void rateLimitsSurviveRejectedAuthenticationAndOpenApiLoads() throws Exception {
        Fixture a=fixture(Role.CLINIC_ADMIN);
        for(int i=0;i<10;i++) mvc.perform(post("/api/v1/session").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("login",a.login(),"password","incorrect-password")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/session").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("login",a.login(),"password",PASSWORD)))).andExpect(status().isTooManyRequests());
        tx.execute(()->db.execute("DELETE FROM auth_rate_limit"));
        mvc.perform(get("/api/v1/openapi").cookie(login(a))).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").exists());
    }
    @Test void payerAndCategoryConfigurationEnforcesPermissionsScopeConcurrencyAndDraftRuleGate() throws Exception {
        Fixture managerA = fixture(Role.BILLING_MANAGER);
        Fixture adminA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-admin-" + UUID.randomUUID());
        Fixture staffA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-staff-" + UUID.randomUUID());
        Fixture reviewerA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-rev-" + UUID.randomUUID());
        Fixture viewerA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-view-" + UUID.randomUUID());
        UUID adminMember = tx.execute(() -> {
            identities.createUser(adminA.user(), adminA.login());
            identities.credentials(adminA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), adminA.user(), Set.of(Role.CLINIC_ADMIN));
        });
        UUID staffMember = tx.execute(() -> {
            identities.createUser(staffA.user(), staffA.login());
            identities.credentials(staffA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), staffA.user(), Set.of(Role.BILLING_STAFF));
        });
        UUID reviewerMember = tx.execute(() -> {
            identities.createUser(reviewerA.user(), reviewerA.login());
            identities.credentials(reviewerA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), reviewerA.user(), Set.of(Role.CLAIMS_REVIEWER));
        });
        UUID viewerMember = tx.execute(() -> {
            identities.createUser(viewerA.user(), viewerA.login());
            identities.credentials(viewerA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), viewerA.user(), Set.of(Role.MANAGEMENT_VIEWER));
        });
        Fixture managerB = fixture(Role.BILLING_MANAGER);

        Cookie bmCookie = login(managerA);
        Cookie caCookie = login(new Fixture(managerA.clinic(), adminA.user(), adminMember, adminA.login()));
        Cookie bsCookie = login(new Fixture(managerA.clinic(), staffA.user(), staffMember, staffA.login()));
        Cookie crCookie = login(new Fixture(managerA.clinic(), reviewerA.user(), reviewerMember, reviewerA.login()));
        Cookie mvCookie = login(new Fixture(managerA.clinic(), viewerA.user(), viewerMember, viewerA.login()));
        Cookie bmCookieB = login(managerB);

        String payerBody = "{\"code\":\"ipm_01\",\"typeCode\":\"ipm\",\"originalName\":\"IPM Synthétique Dakar\"}";
        for (Cookie nonManager : List.of(caCookie, bsCookie, crCookie, mvCookie)) {
            mvc.perform(post("/api/v1/payers").cookie(nonManager).with(csrf())
                .contentType("application/json").content(payerBody)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/payers").cookie(mvCookie)).andExpect(status().isForbidden());

        var createdPayerRes = mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(payerBody))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.code").value("IPM_01"))
            .andExpect(jsonPath("$.typeCode").value("IPM"))
            .andExpect(jsonPath("$.originalName").value("IPM Synthétique Dakar"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn();
        String payerId = json.readTree(createdPayerRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(payerBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYER_CODE_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/payers").cookie(bmCookieB).with(csrf())
            .contentType("application/json").content(payerBody))
            .andExpect(status().isCreated());

        for (Cookie reader : List.of(bmCookie, caCookie, bsCookie, crCookie)) {
            mvc.perform(get("/api/v1/payers").cookie(reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
            mvc.perform(get("/api/v1/payers/" + payerId).cookie(reader))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        }
        mvc.perform(get("/api/v1/payers/" + payerId).cookie(bmCookieB)).andExpect(status().isNotFound());

        String updatePayerBody = "{\"code\":\"IPM_01\",\"typeCode\":\"IPM\",\"originalName\":\"IPM Synthétique Dakar Révisé\",\"active\":false}";
        mvc.perform(patch("/api/v1/payers/" + payerId).cookie(bmCookie).with(csrf())
            .contentType("application/json").content(updatePayerBody)).andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/payers/" + payerId).cookie(bmCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json").content(updatePayerBody))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.active").value(false));
        mvc.perform(patch("/api/v1/payers/" + payerId).cookie(bmCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json").content(updatePayerBody))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(patch("/api/v1/payers/" + payerId).cookie(bmCookieB).with(csrf()).header("If-Match", "\"1\"")
            .contentType("application/json").content(updatePayerBody)).andExpect(status().isNotFound());

        String categoryBody = "{\"key\":\"Missing-Attachment\",\"labelFr\":\"Pièce justificative manquante\",\"labelEn\":\"Missing supporting attachment\"}";
        var createdCategoryRes = mvc.perform(post("/api/v1/denial-categories").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(categoryBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.key").value("missing-attachment"))
            .andReturn();
        String categoryId = json.readTree(createdCategoryRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(patch("/api/v1/denial-categories/" + categoryId).cookie(bmCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"key\":\"different-key\",\"labelFr\":\"Pièce\",\"labelEn\":\"Attachment\",\"active\":true}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CATEGORY_KEY_IMMUTABLE"));

        mvc.perform(patch("/api/v1/denial-categories/" + categoryId).cookie(bmCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"key\":\"missing-attachment\",\"labelFr\":\"Pièce justificative absente\",\"labelEn\":\"Missing attachment\",\"active\":true}"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""));

        String terminologyDraft = """
            {
              "ruleKind": "TERMINOLOGY_MAPPING",
              "effectiveFrom": "2026-01-01",
              "config": {
                "terminologyMappings": [
                  {
                    "sourceCode": "REJ-SN-09",
                    "sourceTerm": "Bordereau non signé par le praticien",
                    "categoryKey": "missing-attachment"
                  }
                ],
                "requiredDocumentKeys": [],
                "deadline": null,
                "workflowSteps": []
              }
            }
            """;
        var rule1Res = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(terminologyDraft))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(1))
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.canDriveDueDate").value(false))
            .andExpect(jsonPath("$.approvalAvailable").value(true))
            .andExpect(jsonPath("$.config.terminologyMappings[0].sourceCode").value("REJ-SN-09"))
            .andExpect(jsonPath("$.config.terminologyMappings[0].sourceTerm").value("Bordereau non signé par le praticien"))
            .andExpect(jsonPath("$.config.terminologyMappings[0].categoryKey").value("missing-attachment"))
            .andReturn();
        String rule1Id = json.readTree(rule1Res.getResponse().getContentAsString()).get("id").asString();

        String unknownDeadlineDraft = """
            {
              "ruleKind": "DEADLINE_RULE",
              "config": {
                "terminologyMappings": [],
                "requiredDocumentKeys": [],
                "deadline": {
                  "trigger": "DENIAL_RECEIPT",
                  "offsetValue": null,
                  "unit": "DAYS",
                  "dayBasis": "CALENDAR",
                  "timezone": "Africa/Dakar",
                  "calendarSource": null
                },
                "workflowSteps": []
              }
            }
            """;
        mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(unknownDeadlineDraft))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(2))
            .andExpect(jsonPath("$.deadlineBasis").value("UNKNOWN"))
            .andExpect(jsonPath("$.canDriveDueDate").value(false));

        mvc.perform(post("/api/v1/payer-rule-versions/" + rule1Id + "/approval").cookie(bsCookie).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payer-rule-versions/" + rule1Id + "/approval").cookie(bmCookieB).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payer-rule-versions/" + rule1Id + "/approval").cookie(bmCookie).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("SOURCE_DOCUMENT_REQUIRED"));

        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE payer_rule_version SET state='APPROVED' WHERE id=:id", "id", UUID.fromString(rule1Id)
        )));

        mvc.perform(get("/api/v1/audit-events").cookie(caCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].action").value("PAYER_RULE_DRAFT_CREATED"));
    }
    @Test void manualClaimsEnforceRevisionsAppendOnlySubmissionsScaleFreezeAndImmutability() throws Exception {
        Fixture managerA = fixture(Role.BILLING_MANAGER);
        Fixture adminA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-ca-" + UUID.randomUUID());
        Fixture staffA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-bs-" + UUID.randomUUID());
        Fixture reviewerA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-cr-" + UUID.randomUUID());
        Fixture viewerA = new Fixture(managerA.clinic(), UUID.randomUUID(), null, "synthetic-mv-" + UUID.randomUUID());
        UUID adminMember = tx.execute(() -> {
            identities.createUser(adminA.user(), adminA.login());
            identities.credentials(adminA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), adminA.user(), Set.of(Role.CLINIC_ADMIN));
        });
        UUID staffMember = tx.execute(() -> {
            identities.createUser(staffA.user(), staffA.login());
            identities.credentials(staffA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), staffA.user(), Set.of(Role.BILLING_STAFF));
        });
        UUID reviewerMember = tx.execute(() -> {
            identities.createUser(reviewerA.user(), reviewerA.login());
            identities.credentials(reviewerA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), reviewerA.user(), Set.of(Role.CLAIMS_REVIEWER));
        });
        UUID viewerMember = tx.execute(() -> {
            identities.createUser(viewerA.user(), viewerA.login());
            identities.credentials(viewerA.user(), crypto.encode(PASSWORD), true);
            return identities.addMember(managerA.clinic(), viewerA.user(), Set.of(Role.MANAGEMENT_VIEWER));
        });
        Fixture managerB = fixture(Role.BILLING_MANAGER);

        Cookie bmCookie = login(managerA);
        Cookie caCookie = login(new Fixture(managerA.clinic(), adminA.user(), adminMember, adminA.login()));
        Cookie bsCookie = login(new Fixture(managerA.clinic(), staffA.user(), staffMember, staffA.login()));
        Cookie crCookie = login(new Fixture(managerA.clinic(), reviewerA.user(), reviewerMember, reviewerA.login()));
        Cookie mvCookie = login(new Fixture(managerA.clinic(), viewerA.user(), viewerMember, viewerA.login()));
        Cookie bmCookieB = login(managerB);

        var payerRes = mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"code\":\"IPM_CLAIMS\",\"typeCode\":\"IPM\",\"originalName\":\"IPM Dakar Claims\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String payerId = json.readTree(payerRes.getResponse().getContentAsString()).get("id").asString();

        String draftBody = """
            {
              "payerId": "%s",
              "externalReference": "FAC-SN-2026-101",
              "pseudonymousPatientRef": "PAT-PSEUDO-8821",
              "sourceDate": "2026-02-01",
              "lines": [
                {"serviceCode": "CONS-SPEC", "description": "Consultation spécialisée", "amount": "25000"}
              ]
            }
            """.formatted(payerId);

        for (Cookie forbiddenWriter : List.of(caCookie, crCookie, mvCookie)) {
            mvc.perform(post("/api/v1/claims").cookie(forbiddenWriter).with(csrf())
                .contentType("application/json").content(draftBody))
                .andExpect(status().isForbidden());
        }
        for (Cookie forbiddenReader : List.of(caCookie, mvCookie)) {
            mvc.perform(get("/api/v1/claims").cookie(forbiddenReader))
                .andExpect(status().isForbidden());
        }

        var createdDraftRes = mvc.perform(post("/api/v1/claims").cookie(bsCookie).with(csrf())
            .contentType("application/json").content(draftBody))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.currency").value("XOF"))
            .andExpect(jsonPath("$.currencyScale").value(0))
            .andExpect(jsonPath("$.draftLines[0].submittedAmount.amount").value("25000"))
            .andReturn();
        String claimId = json.readTree(createdDraftRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/claims").cookie(bsCookie).with(csrf())
            .contentType("application/json").content(draftBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLAIM_REFERENCE_ALREADY_EXISTS"));

        mvc.perform(get("/api/v1/claims/" + claimId).cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"0\""));
        mvc.perform(get("/api/v1/claims/" + claimId).cookie(bmCookieB))
            .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/clinic-settings").cookie(caCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"name\":\"Clinique Dakar\",\"locale\":\"fr-SN\",\"timezone\":\"Africa/Dakar\",\"currency\":\"EUR\",\"fractionDigits\":2}"))
            .andExpect(status().isOk());

        String fractionalDraftUpdate = """
            {
              "payerId": "%s",
              "externalReference": "FAC-SN-2026-101",
              "pseudonymousPatientRef": "PAT-PSEUDO-8821",
              "sourceDate": "2026-02-02",
              "lines": [
                {"serviceCode": "CONS-SPEC", "description": "Consultation spécialisée", "amount": "25000.50"}
              ]
            }
            """.formatted(payerId);
        mvc.perform(patch("/api/v1/claims/" + claimId).cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json").content(fractionalDraftUpdate))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_MONETARY_SCALE"));

        String validDraftUpdate = """
            {
              "payerId": "%s",
              "externalReference": "FAC-SN-2026-101",
              "pseudonymousPatientRef": "PAT-PSEUDO-8821",
              "sourceDate": "2026-02-02",
              "lines": [
                {"serviceCode": "CONS-SPEC", "description": "Consultation spécialisée", "amount": "25000"},
                {"serviceCode": "IMG-ECHO", "description": "Échographie abdominale", "amount": "100000"}
              ]
            }
            """.formatted(payerId);
        mvc.perform(patch("/api/v1/claims/" + claimId).cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json").content(validDraftUpdate))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.draftLines.length()").value(2));

        mvc.perform(post("/api/v1/claims/" + claimId + "/registration").cookie(bsCookie).with(csrf()).header("If-Match", "\"1\"")
            .contentType("application/json")
            .content("{\"evidenceDocumentId\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_SOURCE_DOCUMENT"));

        var registeredRes = mvc.perform(post("/api/v1/claims/" + claimId + "/registration").cookie(bsCookie).with(csrf())
            .header("If-Match", "\"1\""))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.state").value("REGISTERED"))
            .andExpect(jsonPath("$.activeRevisionSubmitted").value(false))
            .andExpect(jsonPath("$.draftLines.length()").value(0))
            .andExpect(jsonPath("$.revisions.length()").value(1))
            .andExpect(jsonPath("$.revisions[0].revisionNumber").value(1))
            .andExpect(jsonPath("$.revisions[0].submittedAmount.amount").value("125000"))
            .andExpect(jsonPath("$.revisions[0].submittedAmount.currency").value("XOF"))
            .andExpect(jsonPath("$.revisions[0].submissionState").value("UNSUBMITTED"))
            .andReturn();
        var regJson = json.readTree(registeredRes.getResponse().getContentAsString());
        String rev1Id = regJson.get("revisions").get(0).get("id").asString();
        String rev1Hash = regJson.get("revisions").get(0).get("contentHash").asString();

        mvc.perform(patch("/api/v1/claims/" + claimId).cookie(bsCookie).with(csrf()).header("If-Match", "\"2\"")
            .contentType("application/json").content(validDraftUpdate))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE_AS_DRAFT"));

        String submitR1Body = """
            {
              "revisionId": "%s",
              "eventKind": "SUBMISSION",
              "channelCode": "bordereau_papier",
              "externalSubmissionRef": "BORD-2026-01",
              "occurredOn": "2026-02-05",
              "occurrencePrecision": "DATE"
            }
            """.formatted(rev1Id);
        var sub1Res = mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "sub-r1-key-1")
            .contentType("application/json").content(submitR1Body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.channelCode").value("BORDEREAU_PAPIER"))
            .andExpect(jsonPath("$.superseded").value(false))
            .andReturn();
        String sub1Id = json.readTree(sub1Res.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "sub-r1-key-1")
            .contentType("application/json").content(submitR1Body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(sub1Id));

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "sub-r1-key-1")
            .contentType("application/json").content(submitR1Body.replace("BORD-2026-01", "BORD-DIFF")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "sub-r1-key-2")
            .contentType("application/json").content(submitR1Body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REVISION_ALREADY_SUBMITTED"));

        String metadataCorrectionBody = """
            {
              "revisionId": "%s",
              "eventKind": "METADATA_CORRECTION",
              "channelCode": "PORTAIL_IPM",
              "externalSubmissionRef": "BORD-2026-01-CORR",
              "occurredOn": "2026-02-06",
              "occurrencePrecision": "DATE",
              "supersedesId": "%s",
              "correctionReason": "Correction du numéro de bordereau et du canal"
            }
            """.formatted(rev1Id, sub1Id);

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "corr-r1-staff")
            .contentType("application/json").content(metadataCorrectionBody))
            .andExpect(status().isForbidden());

        var corrRes = mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bmCookie).with(csrf()).header("Idempotency-Key", "corr-r1-bm")
            .contentType("application/json").content(metadataCorrectionBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.supersedesId").value(sub1Id))
            .andReturn();
        String corrId = json.readTree(corrRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bmCookie).with(csrf()).header("Idempotency-Key", "corr-r1-branch")
            .contentType("application/json").content(metadataCorrectionBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUBMISSION_EVENT_SUPERSEDED"));

        String resubBody = """
            {
              "revisionId": "%s",
              "eventKind": "RESUBMISSION",
              "channelCode": "PORTAIL_IPM",
              "externalSubmissionRef": "BORD-2026-02-RESUB",
              "occurredOn": "2026-02-10",
              "occurredAt": "2026-02-10T14:30:00Z",
              "occurrencePrecision": "INSTANT",
              "resubmissionOfId": "%s",
              "correctionReason": "Renvoi demandé par le gestionnaire IPM"
            }
            """.formatted(rev1Id, corrId);
        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf()).header("Idempotency-Key", "resub-r1-1")
            .contentType("application/json").content(resubBody))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/claims/" + claimId + "/submission-records").cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].superseded").value(true))
            .andExpect(jsonPath("$[1].superseded").value(false))
            .andExpect(jsonPath("$[2].superseded").value(false));

        String amendR2Body = """
            {
              "sourceDate": "2026-02-12",
              "changeReason": "Ajout acte de biologie oublié sur la facture initiale",
              "lines": [
                {"serviceCode": "CONS-SPEC", "description": "Consultation spécialisée", "amount": "25000"},
                {"serviceCode": "IMG-ECHO", "description": "Échographie abdominale", "amount": "100000"},
                {"serviceCode": "BIO-CRP", "description": "Dosage CRP", "amount": "15000"}
              ]
            }
            """;
        mvc.perform(post("/api/v1/claims/" + claimId + "/revisions")
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"5\"")
            .contentType("application/json").content(amendR2Body))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/claims/" + claimId + "/revisions")
            .cookie(bmCookie).with(csrf()).header("If-Match", "\"5\"")
            .contentType("application/json").content(amendR2Body))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"6\""))
            .andExpect(jsonPath("$.state").value("SUBMITTED"))
            .andExpect(jsonPath("$.activeRevisionSubmitted").value(false))
            .andExpect(jsonPath("$.revisions.length()").value(2))
            .andExpect(jsonPath("$.revisions[0].revisionNumber").value(1))
            .andExpect(jsonPath("$.revisions[0].submittedAmount.amount").value("125000"))
            .andExpect(jsonPath("$.revisions[0].contentHash").value(rev1Hash))
            .andExpect(jsonPath("$.revisions[0].submissionState").value("SUBMITTED"))
            .andExpect(jsonPath("$.revisions[0].externalSubmissionCount").value(2))
            .andExpect(jsonPath("$.revisions[0].effectiveExternalSubmissionRef").value("BORD-2026-02-RESUB"))
            .andExpect(jsonPath("$.revisions[1].revisionNumber").value(2))
            .andExpect(jsonPath("$.revisions[1].submittedAmount.amount").value("140000"))
            .andExpect(jsonPath("$.revisions[1].submissionState").value("UNSUBMITTED"))
            .andExpect(jsonPath("$.revisions[1].externalSubmissionCount").value(0));

        mvc.perform(post("/api/v1/claims/" + claimId + "/state-transitions")
            .cookie(bmCookie).with(csrf()).header("If-Match", "\"6\"")
            .contentType("application/json").content("{\"action\":\"VOID\",\"reason\":\"Tentative annulation\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLAIM_VOID_NOT_ALLOWED"));

        mvc.perform(post("/api/v1/claims/" + claimId + "/state-transitions")
            .cookie(bmCookie).with(csrf()).header("If-Match", "\"6\"")
            .contentType("application/json").content("{\"action\":\"ARCHIVE\",\"reason\":\"Clôture exercice mensuel\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"7\""))
            .andExpect(jsonPath("$.state").value("ARCHIVED"))
            .andExpect(jsonPath("$.stateReason").value("Clôture exercice mensuel"));

        mvc.perform(post("/api/v1/claims/" + claimId + "/state-transitions")
            .cookie(bmCookie).with(csrf()).header("If-Match", "\"7\"")
            .contentType("application/json").content("{\"action\":\"REOPEN\",\"reason\":\"Réouverture pour suivi\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"8\""))
            .andExpect(jsonPath("$.state").value("SUBMITTED"));

        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE claim_revision SET submitted_amount = 999 WHERE id = :id", "id", UUID.fromString(rev1Id)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "DELETE FROM claim_line WHERE revision_id = :id", "id", UUID.fromString(rev1Id)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE claim_submission SET external_submission_ref = 'MUTATED' WHERE id = :id", "id", UUID.fromString(sub1Id)
        )));
    }

    @Test
    void documentsEnforceQuarantineScanOutageExtractionCorrectionAndSourcedRuleApproval() throws Exception {
        Fixture bmA = fixture(Role.BILLING_MANAGER);
        Fixture clinicB = fixture(Role.BILLING_MANAGER);
        UUID bsUser = UUID.randomUUID();
        UUID crUser = UUID.randomUUID();
        UUID caUser = UUID.randomUUID();
        UUID mvUser = UUID.randomUUID();
        UUID bsMember = tx.execute(() -> {
            identities.createUser(bsUser, "synthetic-bs-doc-" + bsUser);
            identities.credentials(bsUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), bsUser, Set.of(Role.BILLING_STAFF));
        });
        UUID crMember = tx.execute(() -> {
            identities.createUser(crUser, "synthetic-cr-doc-" + crUser);
            identities.credentials(crUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), crUser, Set.of(Role.CLAIMS_REVIEWER));
        });
        UUID caMember = tx.execute(() -> {
            identities.createUser(caUser, "synthetic-ca-doc-" + caUser);
            identities.credentials(caUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), caUser, Set.of(Role.CLINIC_ADMIN));
        });
        UUID mvMember = tx.execute(() -> {
            identities.createUser(mvUser, "synthetic-mv-doc-" + mvUser);
            identities.credentials(mvUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), mvUser, Set.of(Role.MANAGEMENT_VIEWER));
        });

        Cookie bmCookie = login(bmA);
        Cookie bmCookieB = login(clinicB);
        Cookie bsCookie = login(new Fixture(bmA.clinic(), bsUser, bsMember, "synthetic-bs-doc-" + bsUser));
        Cookie crCookie = login(new Fixture(bmA.clinic(), crUser, crMember, "synthetic-cr-doc-" + crUser));
        Cookie caCookie = login(new Fixture(bmA.clinic(), caUser, caMember, "synthetic-ca-doc-" + caUser));
        Cookie mvCookie = login(new Fixture(bmA.clinic(), mvUser, mvMember, "synthetic-mv-doc-" + mvUser));

        var payerRes = mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"code\":\"IPM-DOC-01\",\"typeCode\":\"IPM\",\"originalName\":\"IPM Dakar Guidance\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String payerId = json.readTree(payerRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/denial-categories").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"key\":\"missing-signature\",\"labelFr\":\"Signature absente\",\"labelEn\":\"Missing signature\"}"))
            .andExpect(status().isCreated());

        var claimRes = mvc.perform(post("/api/v1/claims").cookie(bsCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "payerId": "%s",
                  "externalReference": "FAC-DOC-2026-01",
                  "pseudonymousPatientRef": "PAT-DOC-9001",
                  "sourceDate": "2026-02-05",
                  "lines": [
                    {"serviceCode": "CONS-01", "description": "Consultation", "amount": "30000"}
                  ]
                }
                """.formatted(payerId)))
            .andExpect(status().isCreated())
            .andReturn();
        String claimId = json.readTree(claimRes.getResponse().getContentAsString()).get("id").asString();

        byte[] cleanPdfBytes = ("%PDF-1.4\n1 0 obj << /Type /Page >> endobj\nstream\nBT (Guide officiel IPM Dakar delai contestation 30 jours) Tj ET\nendstream\n%%EOF\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        MockMultipartFile spoofedPdf = new MockMultipartFile(
            "file", "spoofed.pdf", "application/pdf", "<html>not a pdf</html>".getBytes()
        );
        mvc.perform(multipart("/api/v1/documents").file(spoofedPdf).param("payerId", payerId)
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DOCUMENT_SIGNATURE_MISMATCH"));

        MockMultipartFile bothParentsFile = new MockMultipartFile(
            "file", "guide.pdf", "application/pdf", cleanPdfBytes
        );
        mvc.perform(multipart("/api/v1/documents").file(bothParentsFile)
            .param("payerId", payerId).param("claimId", claimId)
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_PARENT"));

        byte[] maliciousPdf = ("%PDF-1.4\n1 0 obj << /Type /Page >> endobj\nstream\nBT (RECOZAI-MALICIOUS-TEST-PAYLOAD) Tj ET\nendstream\n%%EOF\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        MockMultipartFile maliciousUpload = new MockMultipartFile(
            "file", "malware.pdf", "application/pdf", maliciousPdf
        );
        mvc.perform(multipart("/api/v1/documents").file(maliciousUpload).param("payerId", payerId)
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("MALICIOUS_DOCUMENT_REJECTED"));

        for (Cookie forbiddenPayerUploader : List.of(bsCookie, crCookie, caCookie, mvCookie)) {
            mvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "guide.pdf", "application/pdf", cleanPdfBytes))
                .param("payerId", payerId)
                .cookie(forbiddenPayerUploader).with(csrf()))
                .andExpect(status().isForbidden());
        }
        for (Cookie forbiddenClaimUploader : List.of(crCookie, caCookie, mvCookie)) {
            mvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "claim.pdf", "application/pdf", cleanPdfBytes))
                .param("claimId", claimId)
                .cookie(forbiddenClaimUploader).with(csrf()))
                .andExpect(status().isForbidden());
        }

        // Simulate scanner outage (fail-closed quarantine)
        scanner.setAvailable(false);
        var quarantinedRes = mvc.perform(multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", "convention-ipm-2026.pdf", "application/pdf", cleanPdfBytes))
            .param("payerId", payerId)
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.quarantineStatus").value("QUARANTINED"))
            .andExpect(jsonPath("$.scanStatus").value("SCANNER_UNAVAILABLE"))
            .andReturn();
        String payerDocId = json.readTree(quarantinedRes.getResponse().getContentAsString()).get("id").asString();

        // Quarantined document cannot be downloaded, extracted, or used for rule approval
        mvc.perform(get("/api/v1/documents/" + payerDocId + "/content").cookie(bmCookie))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCUMENT_QUARANTINED"));
        mvc.perform(post("/api/v1/documents/" + payerDocId + "/extractions").cookie(bmCookie).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCUMENT_QUARANTINED"));

        String deadlineDraft1 = """
            {
              "ruleKind": "DEADLINE_RULE",
              "effectiveFrom": "2026-01-01",
              "config": {
                "terminologyMappings": [],
                "requiredDocumentKeys": [],
                "deadline": {
                  "trigger": "DENIAL_RECEIPT",
                  "offsetValue": 30,
                  "unit": "DAYS",
                  "dayBasis": "CALENDAR",
                  "timezone": "Africa/Dakar",
                  "calendarSource": "senegal-civil"
                },
                "workflowSteps": []
              }
            }
            """;
        var ruleDraft1Res = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(deadlineDraft1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.canDriveDueDate").value(false))
            .andReturn();
        String deadlineRule1Id = json.readTree(ruleDraft1Res.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payer-rule-versions/" + deadlineRule1Id + "/approval")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_SOURCE_DOCUMENT"));

        // Restore scanner and release quarantined document
        scanner.setAvailable(true);
        mvc.perform(post("/api/v1/documents/" + payerDocId + "/scan-retries")
            .cookie(bmCookie).with(csrf()).header("If-Match", "\"0\""))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.quarantineStatus").value("CLEAN"))
            .andExpect(jsonPath("$.scanStatus").value("CLEAN"));

        // Authorized download vs forbidden/foreign clinic
        mvc.perform(get("/api/v1/documents/" + payerDocId + "/content").cookie(bmCookie))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"document-" + payerDocId + ".pdf\""))
            .andExpect(content().bytes(cleanPdfBytes));

        mvc.perform(get("/api/v1/documents/" + payerDocId + "/content").cookie(mvCookie))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/documents/" + payerDocId + "/content").cookie(bmCookieB))
            .andExpect(status().isNotFound());

        // Extraction and human correction provenance (AC-07)
        var extractRes = mvc.perform(post("/api/v1/documents/" + payerDocId + "/extractions")
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].versionNumber").value(1))
            .andExpect(jsonPath("$[0].pageNumber").value(1))
            .andExpect(jsonPath("$[0].regionLabel").value("PAGE_1"))
            .andExpect(jsonPath("$[0].qualityFlag").value("EXTRACTED"))
            .andExpect(jsonPath("$[0].reviewerCorrection").value(false))
            .andExpect(jsonPath("$[0].extractedText").value("Guide officiel IPM Dakar delai contestation 30 jours"))
            .andReturn();
        String evidenceV1Id = json.readTree(extractRes.getResponse().getContentAsString()).get(0).get("id").asString();

        mvc.perform(post("/api/v1/evidence/" + evidenceV1Id + "/corrections")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "extractedText": "Article 12 : délai de contestation de 30 jours calendaires après réception du rejet",
                  "regionLabel": "PAGE_1_ART_12",
                  "correctionReason": "Précision du numéro d'article sur le guide IPM"
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(2))
            .andExpect(jsonPath("$.pageNumber").value(1))
            .andExpect(jsonPath("$.regionLabel").value("PAGE_1_ART_12"))
            .andExpect(jsonPath("$.qualityFlag").value("HUMAN_VERIFIED"))
            .andExpect(jsonPath("$.reviewerCorrection").value(true))
            .andExpect(jsonPath("$.supersedesId").value(evidenceV1Id));

        // Second correction on already superseded v1 is rejected
        mvc.perform(post("/api/v1/evidence/" + evidenceV1Id + "/corrections")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"extractedText\":\"Autre\",\"correctionReason\":\"Doublon\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EVIDENCE_VERSION_SUPERSEDED"));

        // Verify original v1 row is preserved unchanged alongside v2
        mvc.perform(get("/api/v1/documents/" + payerDocId + "/evidence").cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(evidenceV1Id))
            .andExpect(jsonPath("$[0].versionNumber").value(1))
            .andExpect(jsonPath("$[0].regionLabel").value("PAGE_1"))
            .andExpect(jsonPath("$[0].extractedText").value("Guide officiel IPM Dakar delai contestation 30 jours"))
            .andExpect(jsonPath("$[1].versionNumber").value(2))
            .andExpect(jsonPath("$[1].supersedesId").value(evidenceV1Id));

        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE evidence_version SET extracted_text = 'TAMPERED' WHERE id = :id",
            "id", UUID.fromString(evidenceV1Id)
        )));

        // Sourced payer rule approval & per-rule_kind supersession (AC-02, AR-11)
        String termDraftBody = """
            {
              "ruleKind": "TERMINOLOGY_MAPPING",
              "effectiveFrom": "2026-01-01",
              "config": {
                "terminologyMappings": [
                  {"sourceCode": "IPM-SIG-01", "sourceTerm": "Cachet absent", "categoryKey": "missing-signature"}
                ],
                "requiredDocumentKeys": [],
                "deadline": null,
                "workflowSteps": []
              }
            }
            """;
        var termDraftRes = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(termDraftBody))
            .andExpect(status().isCreated())
            .andReturn();
        String termRuleId = json.readTree(termDraftRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payer-rule-versions/" + termRuleId + "/approval")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("APPROVED"))
            .andExpect(jsonPath("$.sourceDocumentId").value(payerDocId));

        mvc.perform(post("/api/v1/payer-rule-versions/" + deadlineRule1Id + "/approval")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("APPROVED"))
            .andExpect(jsonPath("$.deadlineBasis").value("APPROVED_SOURCED"))
            .andExpect(jsonPath("$.canDriveDueDate").value(true));

        // Create and approve a second DEADLINE_RULE; verify DEADLINE_RULE #1 is SUPERSEDED while TERMINOLOGY_MAPPING stays APPROVED
        String deadlineDraft2 = """
            {
              "ruleKind": "DEADLINE_RULE",
              "effectiveFrom": "2026-03-01",
              "config": {
                "terminologyMappings": [],
                "requiredDocumentKeys": [],
                "deadline": {
                  "trigger": "DENIAL_RECEIPT",
                  "offsetValue": 45,
                  "unit": "DAYS",
                  "dayBasis": "CALENDAR",
                  "timezone": "Africa/Dakar",
                  "calendarSource": "senegal-civil"
                },
                "workflowSteps": []
              }
            }
            """;
        var ruleDraft2Res = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json").content(deadlineDraft2))
            .andExpect(status().isCreated())
            .andReturn();
        String deadlineRule2Id = json.readTree(ruleDraft2Res.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payer-rule-versions/" + deadlineRule2Id + "/approval")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("APPROVED"))
            .andExpect(jsonPath("$.canDriveDueDate").value(true));

        mvc.perform(get("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.id=='" + deadlineRule2Id + "')].state").value("APPROVED"))
            .andExpect(jsonPath("$.items[?(@.id=='" + deadlineRule1Id + "')].state").value("SUPERSEDED"))
            .andExpect(jsonPath("$.items[?(@.id=='" + deadlineRule1Id + "')].canDriveDueDate").value(false))
            .andExpect(jsonPath("$.items[?(@.id=='" + termRuleId + "')].state").value("APPROVED"));

        // Upload a clean claim document and link it to claim registration and submission (AR-10)
        var claimDocRes = mvc.perform(multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", "bordereau-signe.pdf", "application/pdf", cleanPdfBytes))
            .param("claimId", claimId)
            .cookie(bsCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.quarantineStatus").value("CLEAN"))
            .andReturn();
        String claimDocId = json.readTree(claimDocRes.getResponse().getContentAsString()).get("id").asString();

        // Payer guidance document cannot be used as claim revision evidence
        mvc.perform(post("/api/v1/claims/" + claimId + "/registration")
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"evidenceDocumentId\":\"" + payerDocId + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_SOURCE_DOCUMENT"));

        var regClaimRes = mvc.perform(post("/api/v1/claims/" + claimId + "/registration")
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"evidenceDocumentId\":\"" + claimDocId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revisions[0].evidenceDocumentId").value(claimDocId))
            .andReturn();
        String revId = json.readTree(regClaimRes.getResponse().getContentAsString()).get("revisions").get(0).get("id").asString();

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "sub-with-doc-001")
            .contentType("application/json")
            .content("""
                {
                  "revisionId": "%s",
                  "eventKind": "SUBMISSION",
                  "channelCode": "DEPOT_PHYSIQUE",
                  "externalSubmissionRef": "BORD-DOC-2026-01",
                  "occurredOn": "2026-02-06",
                  "occurrencePrecision": "DATE",
                  "evidenceDocumentId": "%s"
                }
                """.formatted(revId, claimDocId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.evidenceDocumentId").value(claimDocId));
    }

    @Test
    void financialLedgerDenialRecoveryAndAppealsEnforceFiveLifecyclesProvenanceAndTenantIsolation() throws Exception {
        Fixture bmA = fixture(Role.BILLING_MANAGER);
        Fixture clinicB = fixture(Role.BILLING_MANAGER);
        UUID bsUser = UUID.randomUUID();
        UUID crUser = UUID.randomUUID();
        UUID caUser = UUID.randomUUID();
        UUID mvUser = UUID.randomUUID();
        UUID bsMember = tx.execute(() -> {
            identities.createUser(bsUser, "synthetic-bs-rec-" + bsUser);
            identities.credentials(bsUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), bsUser, Set.of(Role.BILLING_STAFF));
        });
        UUID crMember = tx.execute(() -> {
            identities.createUser(crUser, "synthetic-cr-rec-" + crUser);
            identities.credentials(crUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), crUser, Set.of(Role.CLAIMS_REVIEWER));
        });
        UUID caMember = tx.execute(() -> {
            identities.createUser(caUser, "synthetic-ca-rec-" + caUser);
            identities.credentials(caUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), caUser, Set.of(Role.CLINIC_ADMIN));
        });
        UUID mvMember = tx.execute(() -> {
            identities.createUser(mvUser, "synthetic-mv-rec-" + mvUser);
            identities.credentials(mvUser, crypto.encode(PASSWORD), true);
            return identities.addMember(bmA.clinic(), mvUser, Set.of(Role.MANAGEMENT_VIEWER));
        });

        Cookie bmCookie = login(bmA);
        Cookie bmCookieB = login(clinicB);
        Cookie bsCookie = login(new Fixture(bmA.clinic(), bsUser, bsMember, "synthetic-bs-rec-" + bsUser));
        Cookie crCookie = login(new Fixture(bmA.clinic(), crUser, crMember, "synthetic-cr-rec-" + crUser));
        Cookie caCookie = login(new Fixture(bmA.clinic(), caUser, caMember, "synthetic-ca-rec-" + caUser));
        Cookie mvCookie = login(new Fixture(bmA.clinic(), mvUser, mvMember, "synthetic-mv-rec-" + mvUser));

        // 1. Setup Payer with Sourced Rules (Terminology, Deadline, Required Docs, Workflow Steps)
        var payerRes = mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"code\":\"IPM-REC-01\",\"typeCode\":\"IPM\",\"originalName\":\"IPM Prévoyance Dakar\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String payerId = json.readTree(payerRes.getResponse().getContentAsString()).get("id").asString();

        var unsourcedPayerRes = mvc.perform(post("/api/v1/payers").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"code\":\"MUT-UNSOURCED\",\"typeCode\":\"MUTUELLE\",\"originalName\":\"Mutuelle Sans Règle\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String unsourcedPayerId = json.readTree(unsourcedPayerRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/denial-categories").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"key\":\"missing-prior-agreement\",\"labelFr\":\"Entente préalable absente\",\"labelEn\":\"Missing prior agreement\"}"))
            .andExpect(status().isCreated());

        byte[] cleanPdfBytes = ("%PDF-1.4\n1 0 obj << /Type /Page >> endobj\nstream\nBT (Convention IPM Dakar Art 14 delai recours 20 jours) Tj ET\nendstream\n%%EOF\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var payerDocRes = mvc.perform(multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", "convention-ipm.pdf", "application/pdf", cleanPdfBytes))
            .param("payerId", payerId)
            .cookie(bmCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String payerDocId = json.readTree(payerDocRes.getResponse().getContentAsString()).get("id").asString();

        var termRuleRes = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "ruleKind": "TERMINOLOGY_MAPPING",
                  "effectiveFrom": "2026-01-01",
                  "sourceLocator": "Page 3, Tableau B",
                  "config": {
                    "terminologyMappings": [
                      {"sourceCode": "REJ-EP-09", "sourceTerm": "Défaut d'entente préalable médicale", "categoryKey": "missing-prior-agreement"}
                    ],
                    "requiredDocumentKeys": [],
                    "deadline": null,
                    "workflowSteps": []
                  }
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceLocator").value("Page 3, Tableau B"))
            .andExpect(jsonPath("$.verificationStatus").value("UNVERIFIED_DRAFT"))
            .andReturn();
        String termRuleId = json.readTree(termRuleRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payer-rule-versions/" + termRuleId + "/approval").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\",\"sourceLocator\":\"Page 3, Tableau B (Vérifié)\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("APPROVED"))
            .andExpect(jsonPath("$.verificationStatus").value("SOURCE_VERIFIED"))
            .andExpect(jsonPath("$.sourceLocator").value("Page 3, Tableau B (Vérifié)"));

        var deadlineRuleRes = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "ruleKind": "DEADLINE_RULE",
                  "effectiveFrom": "2026-01-01",
                  "config": {
                    "terminologyMappings": [],
                    "requiredDocumentKeys": [],
                    "deadline": {
                      "trigger": "DENIAL_RECEIPT",
                      "offsetValue": 20,
                      "unit": "DAYS",
                      "dayBasis": "CALENDAR",
                      "timezone": "Africa/Dakar",
                      "calendarSource": "senegal-civil"
                    },
                    "workflowSteps": []
                  }
                }
                """))
            .andExpect(status().isCreated())
            .andReturn();
        String deadlineRuleId = json.readTree(deadlineRuleRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/payer-rule-versions/" + deadlineRuleId + "/approval").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\",\"sourceLocator\":\"Article 14 alinéa 2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canDriveDueDate").value(true))
            .andExpect(jsonPath("$.verificationStatus").value("SOURCE_VERIFIED"));

        var reqDocRuleRes = mvc.perform(post("/api/v1/payers/" + payerId + "/rule-versions").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "ruleKind": "REQUIRED_DOCUMENTS",
                  "effectiveFrom": "2026-01-01",
                  "config": {
                    "terminologyMappings": [],
                    "requiredDocumentKeys": ["prior-agreement-form", "medical-justification"],
                    "deadline": null,
                    "workflowSteps": []
                  }
                }
                """))
            .andExpect(status().isCreated())
            .andReturn();
        String reqDocRuleId = json.readTree(reqDocRuleRes.getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/payer-rule-versions/" + reqDocRuleId + "/approval").cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"sourceDocumentId\":\"" + payerDocId + "\",\"sourceLocator\":\"Annexe II\"}"))
            .andExpect(status().isOk());

        // 2. Create, register, and submit claim for 150000 XOF
        var claimRes = mvc.perform(post("/api/v1/claims").cookie(bsCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "payerId": "%s",
                  "externalReference": "FAC-REC-2026-001",
                  "pseudonymousPatientRef": "PAT-REC-1001",
                  "sourceDate": "2026-02-01",
                  "lines": [
                    {"serviceCode": "CHIR-01", "description": "Acte chirurgical ambulatoire", "amount": "100000"},
                    {"serviceCode": "IMAG-02", "description": "Scanner thoracique", "amount": "50000"}
                  ]
                }
                """.formatted(payerId)))
            .andExpect(status().isCreated())
            .andReturn();
        String claimId = json.readTree(claimRes.getResponse().getContentAsString()).get("id").asString();

        var regClaimRes = mvc.perform(post("/api/v1/claims/" + claimId + "/registration")
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\""))
            .andExpect(status().isOk())
            .andReturn();
        String revId = json.readTree(regClaimRes.getResponse().getContentAsString()).get("revisions").get(0).get("id").asString();

        // Recording a ledger entry before external claim submission is rejected with 409 CLAIM_NOT_SUBMITTED
        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-before-submit")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "INITIAL_PAYMENT",
                  "amount": "50000",
                  "occurredOn": "2026-02-15",
                  "reason": "Tentative avant soumission"
                }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLAIM_NOT_SUBMITTED"));

        mvc.perform(post("/api/v1/claims/" + claimId + "/submission-records")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "sub-rec-claim-1")
            .contentType("application/json")
            .content("""
                {
                  "revisionId": "%s",
                  "eventKind": "SUBMISSION",
                  "channelCode": "BORDEREAU_PAPIER",
                  "externalSubmissionRef": "BORD-REC-2026-001",
                  "occurredOn": "2026-02-05",
                  "occurrencePrecision": "DATE"
                }
                """.formatted(revId)))
            .andExpect(status().isCreated());

        // Upload clean rejection slip document for the claim
        var claimDocRes = mvc.perform(multipart("/api/v1/documents")
            .file(new MockMultipartFile("file", "rejet-ipm-001.pdf", "application/pdf", cleanPdfBytes))
            .param("claimId", claimId)
            .cookie(bsCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String claimDocId = json.readTree(claimDocRes.getResponse().getContentAsString()).get("id").asString();

        var extractRes = mvc.perform(post("/api/v1/documents/" + claimDocId + "/extractions").cookie(bsCookie).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String evidenceVersionId = json.readTree(extractRes.getResponse().getContentAsString()).get(0).get("id").asString();

        // 3. Financial Ledger: initial payment, idempotency, contractual adjustment, reversal, RBAC
        mvc.perform(get("/api/v1/claims/" + claimId + "/ledger").cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentStatus").value("UNPAID_PENDING"))
            .andExpect(jsonPath("$.submittedAmount.amount").value("150000"))
            .andExpect(jsonPath("$.outstandingAmount.amount").value("150000"));

        String pay1Body = """
            {
              "entryType": "INITIAL_PAYMENT",
              "amount": "50000",
              "occurredOn": "2026-02-15",
              "payerReference": "VIR-IPM-2026-09",
              "sourceDocumentId": "%s",
              "reason": "Règlement partiel de l'acte d'imagerie"
            }
            """.formatted(claimDocId);

        for (Cookie forbiddenLedgerWriter : List.of(crCookie, caCookie, mvCookie)) {
            mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
                .cookie(forbiddenLedgerWriter).with(csrf())
                .header("Idempotency-Key", "led-forbid-" + UUID.randomUUID())
                .contentType("application/json").content(pay1Body))
                .andExpect(status().isForbidden());
        }

        var pay1Res = mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-pay-1")
            .contentType("application/json").content(pay1Body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.previousOutstandingAmount.amount").value("150000"))
            .andExpect(jsonPath("$.resultingOutstandingAmount.amount").value("100000"))
            .andReturn();
        String pay1EntryId = json.readTree(pay1Res.getResponse().getContentAsString()).get("id").asString();

        // Idempotent replay returns identical state; modified payload on same key fails with 409
        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-pay-1")
            .contentType("application/json").content(pay1Body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(pay1EntryId));

        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-pay-1")
            .contentType("application/json").content(pay1Body.replace("50000", "45000")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        // Contractual adjustment followed by reversal
        var adjRes = mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-adj-1")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "CONTRACTUAL_ADJUSTMENT",
                  "amount": "10000",
                  "occurredOn": "2026-02-16",
                  "payerReference": "DEC-001",
                  "reason": "Abattement tarifaire conventionnel erroné"
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resultingOutstandingAmount.amount").value("90000"))
            .andReturn();
        String adjEntryId = json.readTree(adjRes.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-rev-1")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "REVERSAL",
                  "amount": "10000",
                  "occurredOn": "2026-02-16",
                  "reversesEntryId": "%s",
                  "reason": "Annulation de l'abattement saisi par erreur"
                }
                """.formatted(adjEntryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resultingOutstandingAmount.amount").value("100000"));

        mvc.perform(get("/api/v1/claims/" + claimId + "/ledger").cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outstandingAmount.amount").value("100000"))
            .andExpect(jsonPath("$.entries[1].reversed").value(true));

        // Write-off by BILLING_STAFF is forbidden
        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-wo-forbid")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "WRITE_OFF",
                  "amount": "10000",
                  "occurredOn": "2026-02-17",
                  "reason": "Tentative passage en perte non autorisé"
                }
                """))
            .andExpect(status().isForbidden());

        // 4. Denial Intake & Recovery Case Creation with Sourced Due Date and Seeded Tasks
        String denialBody = """
            {
              "denialKind": "PARTIAL",
              "sourceReasonCode": "REJ-EP-09",
              "originalReasonText": "Rejet acte CHIR-01 : absence de formulaire d'entente préalable signé par le médecin conseil",
              "deniedAmount": "100000",
              "noticeDate": "2026-02-14",
              "receivedDate": "2026-02-15",
              "payerReference": "REJ-BORD-2026-77",
              "sourceDocumentId": "%s"
            }
            """.formatted(claimDocId);

        var denialRes = mvc.perform(post("/api/v1/claims/" + claimId + "/denial-events")
            .cookie(crCookie).with(csrf())
            .header("Idempotency-Key", "denial-intake-1")
            .contentType("application/json").content(denialBody))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.summary.caseStatus").value("NEW"))
            .andExpect(jsonPath("$.summary.recoveryOutcome").value("PENDING"))
            .andExpect(jsonPath("$.summary.paymentStatus").value("PARTIALLY_PAID_DISPUTED"))
            .andExpect(jsonPath("$.summary.dueDateBasis").value("APPROVED_SOURCED"))
            .andExpect(jsonPath("$.summary.dueDate").value("2026-03-07"))
            .andExpect(jsonPath("$.summary.dueDateRuleVersionId").value(deadlineRuleId))
            .andExpect(jsonPath("$.primaryDenial.sourceReasonCode").value("REJ-EP-09"))
            .andExpect(jsonPath("$.primaryDenial.normalizedCategoryKey").value("missing-prior-agreement"))
            .andExpect(jsonPath("$.primaryDenial.categoryMappingSource").value("APPROVED_PAYER_RULE"))
            .andExpect(jsonPath("$.primaryDenial.originalReasonText")
                .value("Rejet acte CHIR-01 : absence de formulaire d'entente préalable signé par le médecin conseil"))
            .andExpect(jsonPath("$.tasks.length()").value(2))
            .andReturn();
        var denialJson = json.readTree(denialRes.getResponse().getContentAsString());
        String caseId = denialJson.get("summary").get("id").asString();
        String denialEventId = denialJson.get("primaryDenial").get("id").asString();
        String seededTask1Id = denialJson.get("tasks").get(0).get("id").asString();

        // Verify unsourced payer denial keeps dueDateBasis = UNKNOWN and dueDate = null
        var unsourcedClaimRes = mvc.perform(post("/api/v1/claims").cookie(bsCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "payerId": "%s",
                  "externalReference": "FAC-UNS-2026-001",
                  "pseudonymousPatientRef": "PAT-UNS-1002",
                  "sourceDate": "2026-02-01",
                  "lines": [{"serviceCode": "CONS-01", "description": "Consultation", "amount": "20000"}]
                }
                """.formatted(unsourcedPayerId)))
            .andExpect(status().isCreated())
            .andReturn();
        String unsourcedClaimId = json.readTree(unsourcedClaimRes.getResponse().getContentAsString()).get("id").asString();
        var unsourcedRegRes = mvc.perform(post("/api/v1/claims/" + unsourcedClaimId + "/registration")
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\""))
            .andExpect(status().isOk())
            .andReturn();
        String unsourcedRevId = json.readTree(unsourcedRegRes.getResponse().getContentAsString()).get("revisions").get(0).get("id").asString();
        mvc.perform(post("/api/v1/claims/" + unsourcedClaimId + "/submission-records")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "sub-unsourced-claim-1")
            .contentType("application/json")
            .content("""
                {
                  "revisionId": "%s",
                  "eventKind": "SUBMISSION",
                  "channelCode": "BORDEREAU_PAPIER",
                  "externalSubmissionRef": "BORD-UNS-2026-001",
                  "occurredOn": "2026-02-05",
                  "occurrencePrecision": "DATE"
                }
                """.formatted(unsourcedRevId)))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/claims/" + unsourcedClaimId + "/denial-events")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "denial-unsourced-1")
            .contentType("application/json")
            .content("""
                {
                  "denialKind": "FULL",
                  "originalReasonText": "Motif manuscrit sans règle de délai configurée",
                  "normalizedCategoryKey": "missing-prior-agreement",
                  "deniedAmount": "20000",
                  "noticeDate": "2026-02-15",
                  "receivedDate": "2026-02-15"
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.summary.caseStatus").value("NEW"))
            .andExpect(jsonPath("$.summary.paymentStatus").value("DENIED_UNPAID"))
            .andExpect(jsonPath("$.summary.dueDateBasis").value("UNKNOWN"))
            .andExpect(jsonPath("$.summary.dueDate").isEmpty());

        // 5. Recovery Tasks & Optimistic Locking
        // Completing a required-document task without attaching satisfiedDocumentId fails
        mvc.perform(patch("/api/v1/recovery-cases/" + caseId + "/tasks/" + seededTask1Id)
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"status\":\"COMPLETED\",\"resolutionNote\":\"Tentative sans pièce\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("REQUIRED_DOCUMENT_NOT_ATTACHED"));

        mvc.perform(patch("/api/v1/recovery-cases/" + caseId + "/tasks/" + seededTask1Id)
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("""
                {
                  "status": "COMPLETED",
                  "satisfiedDocumentId": "%s",
                  "resolutionNote": "Formulaire d'entente préalable retrouvé aux archives"
                }
                """.formatted(claimDocId)))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(patch("/api/v1/recovery-cases/" + caseId + "/tasks/" + seededTask1Id)
            .cookie(bsCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("{\"status\":\"CANCELLED\"}"))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // 6. Appeal Drafting, Placeholder Guard, Human Review Gate, and External Submission
        mvc.perform(patch("/api/v1/recovery-cases/" + caseId)
            .cookie(crCookie).with(csrf()).header("If-Match", "\"0\"")
            .contentType("application/json")
            .content("""
                {
                  "caseStatus": "ACTION_REQUIRED",
                  "estimatedRecoverableAmount": "90000",
                  "estimateBasis": "STAFF_CONFIRMED",
                  "estimateNote": "Recouvrable hors franchise annuelle"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.summary.caseStatus").value("ACTION_REQUIRED"))
            .andExpect(jsonPath("$.summary.estimatedRecoverableAmount.amount").value("90000"));

        // Draft v1 with unresolved placeholder [PLACEHOLDER]
        var draft1Res = mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-drafts")
            .cookie(crCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "locale": "fr-SN",
                  "subjectLine": "Recours gracieux facture FAC-REC-2026-001",
                  "bodyText": "Objet : Recours gracieux facture FAC-REC-2026-001. Pièce jointe : [PLACEHOLDER].",
                  "factualBasisSummary": "Entente préalable signée avant l'intervention.",
                  "citedEvidenceIds": ["%s"],
                  "submitForApproval": true
                }
                """.formatted(evidenceVersionId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.unresolvedPlaceholderCount").value(1))
            .andReturn();
        String draft1Id = json.readTree(draft1Res.getResponse().getContentAsString()).get("id").asString();

        // Approving a draft containing [PLACEHOLDER] fails with 422 UNRESOLVED_APPEAL_PLACEHOLDERS
        mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-drafts/" + draft1Id + "/review")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"approved\":true,\"note\":\"Tentative approbation brouillon incomplet\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNRESOLVED_APPEAL_PLACEHOLDERS"));

        // Draft v2 with complete verified text supersedes draft v1
        var draft2Res = mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-drafts")
            .cookie(crCookie).with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "locale": "fr-SN",
                  "subjectLine": "Recours facture FAC-REC-2026-001",
                  "bodyText": "Madame, Monsieur, veuillez trouver ci-joint l'entente préalable signée et le compte rendu opératoire relatifs à la facture FAC-REC-2026-001.",
                  "factualBasisSummary": "Pièce d'entente préalable vérifiée et rattachée au dossier.",
                  "citedEvidenceIds": ["%s"],
                  "submitForApproval": true
                }
                """.formatted(evidenceVersionId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(2))
            .andExpect(jsonPath("$.unresolvedPlaceholderCount").value(0))
            .andReturn();
        String draft2Id = json.readTree(draft2Res.getResponse().getContentAsString()).get("id").asString();

        // Non-BILLING_MANAGER cannot approve appeal draft
        for (Cookie forbiddenApprover : List.of(bsCookie, crCookie, caCookie, mvCookie)) {
            mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-drafts/" + draft2Id + "/review")
                .cookie(forbiddenApprover).with(csrf())
                .contentType("application/json")
                .content("{\"approved\":true,\"note\":\"Non autorisé\"}"))
                .andExpect(status().isForbidden());
        }

        // BILLING_MANAGER approves draft v2
        mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-drafts/" + draft2Id + "/review")
            .cookie(bmCookie).with(csrf())
            .contentType("application/json")
            .content("{\"approved\":true,\"note\":\"Vérifié conforme aux pièces du dossier\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        // Record external appeal submission
        String appealSubBody = """
            {
              "appealDraftVersionId": "%s",
              "channelCode": "COURRIER_DECHARGE",
              "externalSubmissionRef": "DECH-IPM-2026-882",
              "occurredOn": "2026-02-20",
              "occurrencePrecision": "DATE",
              "evidenceDocumentId": "%s",
              "notes": "Déposé contre décharge au siège IPM"
            }
            """.formatted(draft2Id, claimDocId);
        var appealSubRes = mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/appeal-submissions")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "appeal-sub-1")
            .contentType("application/json").content(appealSubBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.externalSubmissionRef").value("DECH-IPM-2026-882"))
            .andReturn();
        String appealSubId = json.readTree(appealSubRes.getResponse().getContentAsString()).get("id").asString();

        // 7. Recovery Payment, Write-off of Remainder, and Case Closure
        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bsCookie).with(csrf())
            .header("Idempotency-Key", "led-rec-pay-1")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "RECOVERY_PAYMENT",
                  "amount": "90000",
                  "occurredOn": "2026-03-02",
                  "payerReference": "VIR-REC-2026-55",
                  "reason": "Règlement suite recours gracieux IPM"
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resultingOutstandingAmount.amount").value("10000"));

        mvc.perform(post("/api/v1/claims/" + claimId + "/ledger-entries")
            .cookie(bmCookie).with(csrf())
            .header("Idempotency-Key", "led-wo-1")
            .contentType("application/json")
            .content("""
                {
                  "entryType": "WRITE_OFF",
                  "amount": "10000",
                  "occurredOn": "2026-03-03",
                  "payerReference": "PV-2026-03",
                  "reason": "Reliquat franchise annuelle non recouvrable"
                }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resultingOutstandingAmount.amount").value("0"));

        var currentCaseRes = mvc.perform(get("/api/v1/recovery-cases/" + caseId).cookie(crCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.caseStatus").value("SUBMITTED_TO_PAYER"))
            .andExpect(jsonPath("$.summary.paymentStatus").value("RECOVERED_PARTIAL"))
            .andExpect(jsonPath("$.appeals[0].versionNumber").value(2))
            .andExpect(jsonPath("$.appeals[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.appeals[1].versionNumber").value(1))
            .andExpect(jsonPath("$.appeals[1].status").value("SUPERSEDED"))
            .andReturn();
        String currentEtag = currentCaseRes.getResponse().getHeader("ETag");

        mvc.perform(patch("/api/v1/recovery-cases/" + caseId)
            .cookie(bmCookie).with(csrf()).header("If-Match", currentEtag)
            .contentType("application/json")
            .content("""
                {
                  "caseStatus": "CLOSED",
                  "recoveryOutcome": "RECOVERED_PARTIAL",
                  "closureReason": "Clôture après recouvrement de 90000 XOF et passage en perte de 10000 XOF"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.caseStatus").value("CLOSED"))
            .andExpect(jsonPath("$.summary.recoveryOutcome").value("RECOVERED_PARTIAL"))
            .andExpect(jsonPath("$.summary.paymentStatus").value("RECOVERED_PARTIAL"));

        // 8. Negative Cross-Tenant Isolation (Clinic B gets 404 on all Clinic A ledger/denial/recovery resources)
        mvc.perform(get("/api/v1/claims/" + claimId + "/ledger").cookie(bmCookieB))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/claims/" + claimId + "/denial-events").cookie(bmCookieB).with(csrf())
            .header("Idempotency-Key", "cross-tenant-denial")
            .contentType("application/json").content(denialBody))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recovery-cases/" + caseId).cookie(bmCookieB))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recovery-cases/" + caseId + "/tasks").cookie(bmCookieB).with(csrf())
            .contentType("application/json").content("{\"taskKind\":\"CUSTOM_FOLLOW_UP\",\"title\":\"Intrusion\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recovery-cases").cookie(bmCookieB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        // 9. Database Trigger Immutability Verification
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE financial_journal_entry SET amount = 1 WHERE id = :id", "id", UUID.fromString(pay1EntryId)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "DELETE FROM financial_journal_entry WHERE id = :id", "id", UUID.fromString(pay1EntryId)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE denial_event SET original_reason_text = 'TAMPERED' WHERE id = :id", "id", UUID.fromString(denialEventId)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "UPDATE appeal_draft_version SET body_text = 'TAMPERED' WHERE id = :id", "id", UUID.fromString(draft2Id)
        )));
        assertThrows(RuntimeException.class, () -> tx.execute(() -> db.execute(
            "DELETE FROM appeal_submission_record WHERE id = :id", "id", UUID.fromString(appealSubId)
        )));
    }
    private String sign(CustodianService.Grant grant) throws Exception {
        byte[] payload=json.writeValueAsBytes(grant);
        Signature signer=Signature.getInstance("Ed25519"); signer.initSign(KEYS.getPrivate()); signer.update(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
    private static KeyPair keys() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch(GeneralSecurityException error) { throw new ExceptionInInitializerError(error); }
    }
}
