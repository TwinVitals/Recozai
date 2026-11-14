import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateLoader, TranslateService, provideTranslateService } from '@ngx-translate/core';
import { App } from './app.component';
import { Api } from './core/api.service';
import { CatalogLoader, en, fr } from './core/catalog';
import { Language } from './core/language.service';
import { ClaimsPage } from './pages/claims';
import { DenialsPage } from './pages/denials';
import { Home } from './pages/home';
import { PayersPage } from './pages/payers';
import { SettingsPage } from './pages/settings';

describe('foundation UI', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService({
          lang: 'fr-SN',
          fallbackLang: 'fr-SN',
          loader: { provide: TranslateLoader, useClass: CatalogLoader },
        }),
      ],
    });
    TestBed.inject(Language).load('fr-SN').subscribe();
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('renders the French shell and an explicit outage rather than a blank app', async () => {
    const fixture = TestBed.createComponent(App);
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/me')
      .flush(
        { code: 'RESTORE_RECONCILIATION_REQUIRED' },
        { status: 503, statusText: 'Unavailable' },
      );
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Recozai');
    expect(fixture.nativeElement.textContent).toContain(fr.errors.RESTORE_RECONCILIATION_REQUIRED);
    expect(document.documentElement.lang).toBe('fr-SN');
  });

  it('preserves a dirty reactive form and identity when language changes', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['CLINIC_ADMIN'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(SettingsPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/clinic-settings').flush({
      id: 'clinic',
      name: 'Synthetic clinic',
      locale: 'fr-SN',
      timezone: 'Africa/Dakar',
      currency: 'XOF',
      fractionDigits: 0,
      version: 3,
    });
    fixture.componentInstance.form.controls.name.setValue('Unsaved synthetic name');
    fixture.componentInstance.form.markAsDirty();
    TestBed.inject(Language).change('en').subscribe();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const update = http.expectOne('/api/v1/me/preferences');
    expect(update.request.headers.get('X-XSRF-TOKEN')).toBe('synthetic-csrf');
    expect(update.request.body).toEqual({ locale: 'en' });
    update.flush(null);
    await fixture.whenStable();
    expect(fixture.componentInstance.form.controls.name.value).toBe('Unsaved synthetic name');
    expect(fixture.componentInstance.form.dirty).toBe(true);
    expect(api.user()?.id).toBe('user');
    expect(fixture.nativeElement.textContent).toContain('Clinic settings');
  });

  it('uses the exact server version for settings updates and preserves stale edits', () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['CLINIC_ADMIN'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(SettingsPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/clinic-settings').flush({
      id: 'clinic',
      name: 'Synthetic',
      locale: 'fr-SN',
      timezone: 'Africa/Dakar',
      currency: 'XOF',
      fractionDigits: 0,
      version: 9,
    });
    fixture.componentInstance.form.controls.name.setValue('Edited');
    fixture.componentInstance.save();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const request = http.expectOne('/api/v1/clinic-settings');
    expect(request.request.headers.get('If-Match')).toBe('"9"');
    request.flush({ code: 'STALE_VERSION' }, { status: 412, statusText: 'Precondition failed' });
    expect(fixture.componentInstance.error()).toBe('errors.STALE_VERSION');
    expect(fixture.componentInstance.form.controls.name.value).toBe('Edited');
  });

  it('keeps catalog keys identical, including errors and roles', () => {
    expect(Object.keys(fr).sort()).toEqual(Object.keys(en).sort());
    expect(Object.keys(fr.roles).sort()).toEqual(Object.keys(en.roles).sort());
    expect(Object.keys(fr.events).sort()).toEqual(Object.keys(en.events).sort());
    expect(Object.keys(fr.errors).sort()).toEqual(Object.keys(en.errors).sort());
    expect(Object.keys(fr.ruleKinds).sort()).toEqual(Object.keys(en.ruleKinds).sort());
    expect(Object.keys(fr.ruleStates).sort()).toEqual(Object.keys(en.ruleStates).sort());
    expect(Object.keys(fr.deadlineBases).sort()).toEqual(Object.keys(en.deadlineBases).sort());
    expect(Object.keys(fr.workflowSteps).sort()).toEqual(Object.keys(en.workflowSteps).sort());
    expect(Object.keys(fr.claimStates).sort()).toEqual(Object.keys(en.claimStates).sort());
    expect(Object.keys(fr.revisionSubmissionStates).sort()).toEqual(
      Object.keys(en.revisionSubmissionStates).sort(),
    );
    expect(Object.keys(fr.submissionEventKinds).sort()).toEqual(
      Object.keys(en.submissionEventKinds).sort(),
    );
    expect(Object.keys(fr.denialCaseStatuses).sort()).toEqual(
      Object.keys(en.denialCaseStatuses).sort(),
    );
    expect(Object.keys(fr.appealStatuses).sort()).toEqual(Object.keys(en.appealStatuses).sort());
    expect(Object.keys(fr.paymentStatuses).sort()).toEqual(Object.keys(en.paymentStatuses).sort());
    expect(Object.keys(fr.recoveryOutcomes).sort()).toEqual(
      Object.keys(en.recoveryOutcomes).sort(),
    );
    expect(Object.keys(fr.deadlineTriggers).sort()).toEqual(
      Object.keys(en.deadlineTriggers).sort(),
    );
    expect(Object.keys(fr.deadlineUnits).sort()).toEqual(Object.keys(en.deadlineUnits).sort());
    expect(Object.keys(fr.deadlineDayBases).sort()).toEqual(
      Object.keys(en.deadlineDayBases).sort(),
    );
  });

  it('renders the Overview Dashboard KPIs, timeframe selector and AI Recovery Assistant in French and English', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'dakar-billing',
      locale: 'fr-SN',
      clinicId: 'clinic-sn',
      membershipId: 'membership-sn',
      roles: ['BILLING_MANAGER'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(Home);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/claims?page=0&size=20').flush({
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(fr.welcome);
    expect(fixture.nativeElement.textContent).toContain('28,4M XOF');
    expect(fixture.nativeElement.textContent).toContain('71%');
    expect(fixture.nativeElement.textContent).toContain(fr.aiAssistantTitle);
    expect(fixture.nativeElement.textContent).toContain(fr.aiAssistiveBadge);

    // Switch timeframe and ask AI assistant
    fixture.componentInstance.workspace.selectedTimeframe.set('90D');
    fixture.componentInstance.runQuickAiPrompt('aiQuickAnalyze');
    fixture.detectChanges();
    expect(fixture.componentInstance.workspace.aiChatLog().length).toBeGreaterThan(0);
    expect(fixture.nativeElement.textContent).toContain('CLM-2026-10472');

    // Switch to English and verify localization
    TestBed.inject(Language).change('en').subscribe();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    http.expectOne('/api/v1/me/preferences').flush(null);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(en.welcome);
    expect(fixture.nativeElement.textContent).toContain(en.aiAssistantTitle);
    expect(fixture.nativeElement.textContent).toContain(en.aiAssistiveBadge);
  });

  it('executes the end-to-end denial recovery workflow with verbatim source preservation and human approval gate', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'dakar-manager',
      locale: 'fr-SN',
      clinicId: 'clinic-sn',
      membershipId: 'membership-sn',
      roles: ['BILLING_MANAGER'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(DenialsPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/claims?page=0&size=50').flush({
      items: [],
      page: 0,
      size: 50,
      totalElements: 0,
    });
    http.expectOne('/api/v1/payers?page=0&size=100').flush({
      items: [],
      page: 0,
      size: 100,
      totalElements: 0,
    });
    http.expectOne('/api/v1/denial-categories?page=0&size=100').flush({
      items: [],
      page: 0,
      size: 100,
      totalElements: 0,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    const active = comp.selectedCase()!;
    expect(active.claimExternalRef).toBe('CLM-2026-10472');
    // Verbatim payer rejection text is preserved untranslated even when UI switches to English
    TestBed.inject(Language).change('en').subscribe();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    http.expectOne('/api/v1/me/preferences').flush(null);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'REJ-IPM-09 : Bordereau de facturation non contresigné',
    );
    expect(fixture.nativeElement.textContent).toContain(
      'Missing supporting attachment or signature',
    );

    // Generate AI draft while second evidence item is still EXTRACTED -> inserts [PLACEHOLDER]
    comp.appealForm.controls.locale.setValue('en');
    comp.generateAiDraft(active.id);
    fixture.detectChanges();
    expect(comp.appealForm.controls.body.value).toContain('[PLACEHOLDER:');
    expect(comp.canApproveAppeal(comp.selectedCase()!)).toBe(false);

    // Human-verify the second evidence item and regenerate clean draft
    comp.verifyEvidenceItem(active.id, 'ev-10472-2');
    comp.generateAiDraft(active.id);
    fixture.detectChanges();
    expect(comp.appealForm.controls.body.value).not.toContain('[PLACEHOLDER:');
    expect(comp.canApproveAppeal(comp.selectedCase()!)).toBe(true);

    // Billing Manager approves the appeal, records external submission, and records XOF recovery
    comp.approveAppeal(active.id);
    expect(comp.selectedCase()!.appealStatus).toBe('APPROVED');

    comp.recordExternalSubmission(active.id);
    expect(comp.selectedCase()!.appealStatus).toBe('SUBMITTED');
    expect(comp.selectedCase()!.caseStatus).toBe('SUBMITTED_TO_PAYER');

    comp.recoveryForm.patchValue({ recoveredAmountXof: 185000, writeOffRemainder: false });
    comp.recordConfirmedRecovery(active.id);
    expect(comp.selectedCase()!.confirmedRecoveredXof).toBe(185000);
    expect(comp.selectedCase()!.paymentStatus).toBe('RECOVERED_FULL');
    expect(comp.selectedCase()!.recoveryOutcome).toBe('RECOVERED_FULL');
  });

  it('renders the claims table and follows server-provided pagination metadata', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic-bm',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['BILLING_MANAGER'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(ClaimsPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/payers?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 0,
    });
    const makeClaim = (id: string, externalReference: string, state: 'DRAFT' | 'SUBMITTED') => ({
      id,
      clinicId: 'clinic',
      payerId: 'payer-1',
      externalReference,
      pseudonymousPatientRef: `PAT-${id}`,
      currency: 'XOF',
      currencyScale: 0,
      state,
      draftSourceDate: state === 'DRAFT' ? '2026-02-01' : null,
      activeRevisionId: state === 'SUBMITTED' ? `revision-${id}` : null,
      activeRevisionNumber: state === 'SUBMITTED' ? 1 : null,
      activeSubmittedAmount: state === 'SUBMITTED' ? { amount: '25000', currency: 'XOF' } : null,
      activeRevisionSubmitted: state === 'SUBMITTED',
      version: 0,
      createdAt: '2026-02-01T10:00:00Z',
      updatedAt: '2026-02-01T10:00:00Z',
    });
    http.expectOne('/api/v1/claims?page=0').flush({
      items: [makeClaim('claim-1', 'FAC-DRAFT-001', 'DRAFT')],
      page: 0,
      size: 1,
      totalElements: 2,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Toutes les demandes');
    expect(fixture.componentInstance.totalClaims()).toBe(2);
    expect(fixture.componentInstance.pageFirst()).toBe(1);

    const nextButton = fixture.nativeElement.querySelector(
      '.claims-page-controls button:last-child',
    ) as HTMLButtonElement | null;
    nextButton?.click();
    const nextPage = http.expectOne('/api/v1/claims?page=1');
    nextPage.flush({
      items: [makeClaim('claim-2', 'FAC-SUBMITTED-002', 'SUBMITTED')],
      page: 1,
      size: 1,
      totalElements: 2,
    });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.pageNumber()).toBe(1);
    expect(fixture.componentInstance.pageFirst()).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('FAC-SUBMITTED-002');
  });

  it('registers manual claims, records append-only submission events and preserves dirty state across locales', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic-bm',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['BILLING_MANAGER'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(ClaimsPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/payers?page=0').flush({
      items: [
        {
          id: 'payer-1',
          clinicId: 'clinic',
          code: 'IPM_SN',
          typeCode: 'IPM',
          originalName: 'IPM Synthétique Dakar',
          active: true,
          version: 0,
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    http.expectOne('/api/v1/claims?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 0,
    });

    fixture.componentInstance.draftForm.patchValue({
      payerId: 'payer-1',
      externalReference: 'FAC-2026-999',
      pseudonymousPatientRef: 'PAT-SN-999',
      sourceDate: '2026-02-01',
      serviceCode: 'CONS-01',
      description: 'Consultation générale',
      amount: '25000',
    });
    fixture.componentInstance.draftForm.markAsDirty();

    TestBed.inject(Language).change('en').subscribe();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    http.expectOne('/api/v1/me/preferences').flush(null);
    await fixture.whenStable();
    expect(fixture.componentInstance.draftForm.controls.externalReference.value).toBe(
      'FAC-2026-999',
    );
    expect(fixture.componentInstance.draftForm.dirty).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Claims');

    fixture.componentInstance.createDraft();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const createReq = http.expectOne('/api/v1/claims');
    expect(createReq.request.body.lines[0].amount).toBe('25000');
    createReq.flush({
      id: 'claim-1',
      clinicId: 'clinic',
      payerId: 'payer-1',
      externalReference: 'FAC-2026-999',
      pseudonymousPatientRef: 'PAT-SN-999',
      currency: 'XOF',
      currencyScale: 0,
      state: 'DRAFT',
      draftSourceDate: '2026-02-01',
      activeRevisionId: null,
      activeRevisionSubmitted: false,
      stateReason: null,
      draftLines: [
        {
          id: 'line-1',
          lineNumber: 1,
          serviceCode: 'CONS-01',
          description: 'Consultation générale',
          submittedAmount: { amount: '25000', currency: 'XOF' },
        },
      ],
      revisions: [],
      version: 0,
      createdBy: 'user',
      createdAt: '2026-02-01T10:00:00Z',
      updatedAt: '2026-02-01T10:00:00Z',
    });
    http.expectOne('/api/v1/claims?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 1,
    });

    const draftDetail = fixture.componentInstance.selectedClaim()!;
    fixture.componentInstance.registerClaim(draftDetail);
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const regReq = http.expectOne('/api/v1/claims/claim-1/registration');
    expect(regReq.request.headers.get('If-Match')).toBe('"0"');
    regReq.flush({
      ...draftDetail,
      state: 'REGISTERED',
      activeRevisionId: 'rev-1',
      draftLines: [],
      revisions: [
        {
          id: 'rev-1',
          revisionNumber: 1,
          submittedAmount: { amount: '25000', currency: 'XOF' },
          sourceDate: '2026-02-01',
          contentHash: 'a'.repeat(64),
          changeReason: null,
          evidenceDocumentId: null,
          submissionState: 'UNSUBMITTED',
          externalSubmissionCount: 0,
          effectiveChannelCode: null,
          effectiveExternalSubmissionRef: null,
          effectiveOccurredOn: null,
          effectiveOccurredAt: null,
          effectiveOccurrencePrecision: null,
          lines: draftDetail.draftLines,
          registeredBy: 'user',
          registeredAt: '2026-02-01T10:05:00Z',
        },
      ],
      version: 1,
    });
    http.expectOne('/api/v1/claims?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('R1 — 25000 XOF');
    expect(fixture.nativeElement.querySelector('p-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('p-table')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('p-tag')).not.toBeNull();
    const detailEl = fixture.nativeElement.querySelector('.claim-detail') as HTMLElement | null;
    expect(detailEl).not.toBeNull();
    expect(detailEl?.querySelector('.claim-detail-title-row')?.textContent).toContain(
      'FAC-2026-999',
    );
    expect(detailEl?.querySelector('.claim-detail-facts')?.textContent).toContain(
      'IPM Synthétique Dakar',
    );
    expect(detailEl?.querySelector('.claim-detail-facts')?.textContent).toContain('25000 XOF');
    expect(detailEl?.querySelector('.claim-lifecycle-stepper')).not.toBeNull();
  });

  it('enforces payer read/manage UI states, dirty-form localization and B08 rule approval gate', async () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic-bm',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['BILLING_MANAGER'],
      memberships: [],
    });
    const fixture = TestBed.createComponent(PayersPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/payers?page=0').flush({
      items: [
        {
          id: 'payer-1',
          clinicId: 'clinic',
          code: 'IPM_SN',
          typeCode: 'IPM',
          originalName: 'IPM Synthétique Dakar',
          active: true,
          version: 2,
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    http.expectOne('/api/v1/denial-categories?page=0').flush({
      items: [
        {
          id: 'cat-1',
          clinicId: 'clinic',
          key: 'missing-attachment',
          labelFr: 'Pièce justificative absente',
          labelEn: 'Missing supporting attachment',
          active: true,
          version: 0,
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    fixture.componentInstance.payerForm.controls.originalName.setValue('Unsaved mutuelle name');
    fixture.componentInstance.payerForm.markAsDirty();
    TestBed.inject(Language).change('en').subscribe();
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    http.expectOne('/api/v1/me/preferences').flush(null);
    await fixture.whenStable();
    expect(fixture.componentInstance.payerForm.controls.originalName.value).toBe(
      'Unsaved mutuelle name',
    );
    expect(fixture.componentInstance.payerForm.dirty).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Payers and rules');
    expect(fixture.nativeElement.textContent).toContain('IPM Synthétique Dakar');

    fixture.componentInstance.selectPayer(fixture.componentInstance.payers()[0]);
    http.expectOne('/api/v1/payers/payer-1/rule-versions?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 0,
    });
    http.expectOne('/api/v1/payers/payer-1/documents?page=0').flush({
      items: [],
      page: 0,
      size: 25,
      totalElements: 0,
    });
    fixture.componentInstance.ruleForm.patchValue({
      ruleKind: 'TERMINOLOGY_MAPPING',
      sourceCode: 'REJ-01',
      sourceTerm: 'Bordereau incomplet',
      categoryKey: 'missing-attachment',
    });
    fixture.componentInstance.createRuleVersion(fixture.componentInstance.payers()[0]);
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const ruleReq = http.expectOne('/api/v1/payers/payer-1/rule-versions');
    expect(ruleReq.request.body.config.terminologyMappings[0].sourceCode).toBe('REJ-01');
    ruleReq.flush({
      id: 'rule-1',
      clinicId: 'clinic',
      payerId: 'payer-1',
      versionNumber: 1,
      state: 'DRAFT',
      ruleKind: 'TERMINOLOGY_MAPPING',
      sourceDocumentId: null,
      approvedBy: null,
      approvedAt: null,
      effectiveFrom: null,
      effectiveTo: null,
      deadlineBasis: 'NOT_APPLICABLE',
      canDriveDueDate: false,
      approvalAvailable: true,
      config: ruleReq.request.body.config,
      createdBy: 'user',
      createdAt: '2026-11-11T00:00:00Z',
    });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(en.ruleApprovalUnavailable);
    expect(fixture.nativeElement.textContent).toContain('REJ-01 — Bordereau incomplet');

    // Upload a clean payer guidance document and approve the draft rule version
    const guidanceFile = new File(['%PDF-1.4\n'], 'guide-ipm.pdf', { type: 'application/pdf' });
    fixture.componentInstance.selectedFile.set(guidanceFile);
    fixture.componentInstance.uploadPayerDocument(fixture.componentInstance.payers()[0]);
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const uploadReq = http.expectOne('/api/v1/documents');
    uploadReq.flush({
      id: 'doc-1',
      clinicId: 'clinic',
      claimId: null,
      payerId: 'payer-1',
      sourceName: 'guide-ipm.pdf',
      mimeType: 'application/pdf',
      byteCount: 9,
      sha256Hash: 'b'.repeat(64),
      quarantineStatus: 'CLEAN',
      scanStatus: 'CLEAN',
      scanDetail: 'SCAN_CLEAN',
      scannedAt: '2026-11-11T00:01:00Z',
      version: 0,
      createdBy: 'user',
      createdAt: '2026-11-11T00:01:00Z',
      updatedAt: '2026-11-11T00:01:00Z',
    });
    await fixture.whenStable();
    expect(fixture.componentInstance.approvalDocumentId.value).toBe('doc-1');

    fixture.componentInstance.approveRuleVersion(
      fixture.componentInstance.payers()[0],
      fixture.componentInstance.ruleVersions()[0],
    );
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    const approveReq = http.expectOne('/api/v1/payer-rule-versions/rule-1/approval');
    expect(approveReq.request.body.sourceDocumentId).toBe('doc-1');
    approveReq.flush({
      id: 'rule-1',
      clinicId: 'clinic',
      payerId: 'payer-1',
      versionNumber: 1,
      state: 'APPROVED',
      ruleKind: 'TERMINOLOGY_MAPPING',
      sourceDocumentId: 'doc-1',
      approvedBy: 'user',
      approvedAt: '2026-11-11T00:02:00Z',
      effectiveFrom: null,
      effectiveTo: null,
      deadlineBasis: 'NOT_APPLICABLE',
      canDriveDueDate: false,
      approvalAvailable: true,
      config: ruleReq.request.body.config,
      createdBy: 'user',
      createdAt: '2026-11-11T00:00:00Z',
    });
    http.expectOne('/api/v1/payers/payer-1/rule-versions?page=0').flush({
      items: [
        {
          id: 'rule-1',
          clinicId: 'clinic',
          payerId: 'payer-1',
          versionNumber: 1,
          state: 'APPROVED',
          ruleKind: 'TERMINOLOGY_MAPPING',
          sourceDocumentId: 'doc-1',
          approvedBy: 'user',
          approvedAt: '2026-11-11T00:02:00Z',
          effectiveFrom: null,
          effectiveTo: null,
          deadlineBasis: 'NOT_APPLICABLE',
          canDriveDueDate: false,
          approvalAvailable: true,
          config: ruleReq.request.body.config,
          createdBy: 'user',
          createdAt: '2026-11-11T00:00:00Z',
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    http.expectOne('/api/v1/payers/payer-1/documents?page=0').flush({
      items: [
        {
          id: 'doc-1',
          clinicId: 'clinic',
          claimId: null,
          payerId: 'payer-1',
          sourceName: 'guide-ipm.pdf',
          mimeType: 'application/pdf',
          byteCount: 9,
          sha256Hash: 'b'.repeat(64),
          quarantineStatus: 'CLEAN',
          scanStatus: 'CLEAN',
          scanDetail: 'SCAN_CLEAN',
          scannedAt: '2026-11-11T00:01:00Z',
          version: 0,
          createdBy: 'user',
          createdAt: '2026-11-11T00:01:00Z',
          updatedAt: '2026-11-11T00:01:00Z',
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
    });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Approved');
  });

  it('does not silently replace an unsupported language', () => {
    let failed = false;
    new CatalogLoader().getTranslation('unsupported').subscribe({
      error: () => {
        failed = true;
      },
    });
    expect(failed).toBe(true);
  });

  it('maps only unauthenticated responses to anonymous state', () => {
    let failure = false;
    const api = TestBed.inject(Api);
    api.refresh().subscribe({
      error: () => {
        failure = true;
      },
    });
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/me')
      .flush({ code: 'DATA_OPERATION_FAILED' }, { status: 503, statusText: 'Unavailable' });
    expect(failure).toBe(true);
    api.refresh().subscribe((user) => expect(user).toBeNull());
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/me')
      .flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    expect(api.user()).toBeNull();
  });

  it('clears revoked UI permissions while preserving the explicit request error', () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['CLINIC_ADMIN'],
      memberships: [],
    });
    let failed = false;
    api.get('/memberships').subscribe({
      error: () => {
        failed = true;
      },
    });
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/memberships')
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });
    expect(api.admin()).toBe(false);
    expect(api.user()).toBeNull();
    expect(failed).toBe(true);
  });

  it('does not pretend a failed owner password check revoked a valid session', () => {
    const api = TestBed.inject(Api);
    api.user.set({
      id: 'user',
      login: 'synthetic',
      locale: 'fr-SN',
      clinicId: 'clinic',
      membershipId: 'membership',
      roles: ['CLINIC_ADMIN'],
      memberships: [],
    });
    let failed = false;
    api
      .mutate('POST', '/me/session-revocations', { password: 'synthetic-wrong-password' })
      .subscribe({
        error: () => {
          failed = true;
        },
      });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/session/csrf').flush({ token: 'synthetic-csrf' });
    http
      .expectOne('/api/v1/me/session-revocations')
      .flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    expect(api.user()?.id).toBe('user');
    expect(failed).toBe(true);
  });
});
