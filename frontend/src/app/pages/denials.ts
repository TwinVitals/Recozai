import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { Api, ClaimSummary, DenialCategory, Page, Payer } from '../core/api.service';
import { Language } from '../core/language.service';
import {
  DenialCaseStatusKey,
  DenialRecoveryCase,
  RecoveryWorkspaceService,
} from '../core/recovery-workspace.service';

@Component({
  imports: [ReactiveFormsModule, TranslatePipe],
  template: `
    <div class="denial-page">
      <header class="hc-page-header card">
        <div>
          <h1>{{ 'denialsTitle' | translate }}</h1>
          <p class="hc-muted">{{ 'denialsSubtitle' | translate }}</p>
        </div>
        <div class="actions hc-m-0">
          @if (workspace.showSyntheticDemo()) {
            <span class="hc-badge hc-badge-warn">◆ {{ 'syntheticDemoBadge' | translate }}</span>
          }
          @if (api.claimWrite()) {
            <button type="button" class="hc-btn" (click)="showCreateForm.set(!showCreateForm())">
              + {{ 'recordDenialTitle' | translate }}
            </button>
          }
        </div>
      </header>

      <!-- Collapsible Record Rejection Form -->
      @if (showCreateForm() && api.claimWrite()) {
        <section class="card">
          <h2>{{ 'recordDenialTitle' | translate }}</h2>
          <form [formGroup]="rejectionForm" (ngSubmit)="submitNewRejection()">
            <div class="hc-grid-2">
              <label>
                {{ 'claimExternalRef' | translate }}
                <input
                  formControlName="claimExternalRef"
                  maxlength="80"
                  placeholder="CLM-2026-10480"
                />
              </label>
              <label>
                {{ 'claimPatientRef' | translate }}
                <input
                  formControlName="pseudonymousPatientRef"
                  maxlength="80"
                  placeholder="PAT-SN-9012"
                />
              </label>
              <label>
                {{ 'payerCode' | translate }}
                <input formControlName="payerCode" maxlength="40" placeholder="IPM_DAKAR_01" />
              </label>
              <label>
                {{ 'payerType' | translate }}
                <select formControlName="payerType">
                  <option value="IPM">IPM</option>
                  <option value="MUTUELLE">Mutuelle</option>
                  <option value="CMU">CMU</option>
                  <option value="INSURER">Assurance</option>
                </select>
              </label>
              <label>
                {{ 'denialSourceCode' | translate }}
                <input formControlName="sourceReasonCode" maxlength="40" placeholder="REJ-IPM-14" />
              </label>
              <label>
                {{ 'denialNoticeDate' | translate }}
                <input type="date" formControlName="noticeDate" />
              </label>
              <label>
                {{ 'denialDeniedAmount' | translate }}
                <input type="number" formControlName="deniedAmountXof" min="1" step="1" />
              </label>
              <label>
                {{ 'denialEstimatedAmount' | translate }}
                <input type="number" formControlName="estimatedRecoverableXof" min="0" step="1" />
              </label>
            </div>
            <label>
              {{ 'denialOriginalWording' | translate }}
              <textarea
                rows="2"
                formControlName="originalReasonText"
                placeholder="Ex: REJ-IPM-14 : Bulletin de prise en charge non joint au bordereau mensuel."
              ></textarea>
            </label>
            <div class="actions">
              <button type="submit">{{ 'createDenialCaseButton' | translate }}</button>
              <button type="button" class="secondary" (click)="showCreateForm.set(false)">
                {{ 'clearFilter' | translate }}
              </button>
            </div>
          </form>
        </section>
      }

      @if (feedbackMessage()) {
        <div class="notice" role="status">{{ feedbackMessage() | translate }}</div>
      }
      @if (errorMessage()) {
        <p class="error" role="alert">{{ errorMessage() | translate }}</p>
      }

      <!-- Filter Bar -->
      <section class="filter-bar">
        <div class="filter-pills" role="group" [attr.aria-label]="'filterByStatus' | translate">
          <button
            type="button"
            class="filter-pill"
            [class.active]="statusFilter() === 'ALL'"
            (click)="statusFilter.set('ALL')"
          >
            {{ 'allStatuses' | translate }} ({{ workspace.visibleCases().length }})
          </button>
          <button
            type="button"
            class="filter-pill"
            [class.active]="statusFilter() === 'ACTION_REQUIRED'"
            (click)="statusFilter.set('ACTION_REQUIRED')"
          >
            ! {{ 'denialCaseStatuses.ACTION_REQUIRED' | translate }}
          </button>
          <button
            type="button"
            class="filter-pill"
            [class.active]="statusFilter() === 'AWAITING_APPROVAL'"
            (click)="statusFilter.set('AWAITING_APPROVAL')"
          >
            ◆ {{ 'denialCaseStatuses.AWAITING_APPROVAL' | translate }}
          </button>
          <button
            type="button"
            class="filter-pill"
            [class.active]="statusFilter() === 'RECOVERED_FULL'"
            (click)="statusFilter.set('RECOVERED_FULL')"
          >
            ✓ {{ 'denialCaseStatuses.RECOVERED_FULL' | translate }}
          </button>
        </div>
      </section>

      <!-- Main Split Workspace: Queue on Left, Case Detail & Appeal Editor on Right -->
      @if (filteredCases().length === 0) {
        <section class="card">
          <p>{{ 'empty' | translate }}</p>
        </section>
      } @else {
        <div class="workspace-split">
          <!-- Left Column: Prioritized Work Queue -->
          <div class="queue-list" role="list" [attr.aria-label]="'denialsTitle' | translate">
            @for (item of filteredCases(); track item.id) {
              <button
                type="button"
                role="listitem"
                class="queue-item"
                [class.selected]="selectedCase().id === item.id"
                (click)="selectCase(item)"
              >
                <div class="queue-item-top">
                  <span class="hc-mono">
                    <strong>{{ item.claimExternalRef }}</strong>
                  </span>
                  <span class="hc-badge" [class]="caseBadgeClass(item.caseStatus)">
                    {{ caseStatusIcon(item.caseStatus) }}
                    {{ 'denialCaseStatuses.' + item.caseStatus | translate }}
                  </span>
                </div>
                <div class="queue-item-top">
                  <span>{{ item.payerCode }} · {{ item.pseudonymousPatientRef }}</span>
                  <span class="hc-amount">
                    {{ item.deniedAmountXof.toLocaleString('fr-SN') }} XOF
                  </span>
                </div>
                <div class="hc-muted">
                  {{ isFrench() ? item.normalizedLabelFr : item.normalizedLabelEn }}
                </div>
                <div class="queue-item-top hc-mt-xs">
                  <span class="hc-badge hc-badge-ai"> ✦ IA {{ item.aiConfidencePct }}% </span>
                  @if (item.deadlineDate) {
                    <span class="hc-badge hc-badge-verified"> ⏱ {{ item.deadlineDate }} </span>
                  } @else {
                    <span class="hc-badge hc-badge-neutral">
                      ⏱ {{ 'deadlineBases.UNKNOWN' | translate }}
                    </span>
                  }
                </div>
              </button>
            }
          </div>

          <!-- Right Column: Selected Denial Case Detail -->
          @if (selectedCase(); as activeCase) {
            <div class="detail-stack">
              <!-- Case Header & 5 Orthogonal Lifecycles -->
              <section class="card">
                <div class="hc-page-header hc-mb-md">
                  <div>
                    <div class="hc-muted hc-mono">{{ activeCase.caseReference }}</div>
                    <h2>{{ 'caseDetailTitle' | translate }} — {{ activeCase.claimExternalRef }}</h2>
                    <p class="hc-muted">
                      {{ activeCase.payerOriginalName }} ({{ activeCase.payerCode }}) ·
                      {{ 'claimPatientRef' | translate }}:
                      <strong class="hc-mono">{{ activeCase.pseudonymousPatientRef }}</strong>
                    </p>
                  </div>
                  @if (activeCase.isSyntheticDemo) {
                    <span class="hc-badge hc-badge-warn">
                      ◆ {{ 'syntheticDemoBadge' | translate }}
                    </span>
                  }
                </div>

                <!-- 5 Orthogonal Statuses required by Domain Model -->
                <div class="lifecycle-strip">
                  <div class="lifecycle-cell">
                    <span class="lifecycle-label">{{ 'statusClaimLabel' | translate }}</span>
                    <span class="hc-badge hc-badge-brand">
                      ● {{ 'claimStates.' + activeCase.claimStatus | translate }}
                    </span>
                  </div>
                  <div class="lifecycle-cell">
                    <span class="lifecycle-label">{{ 'statusDenialCaseLabel' | translate }}</span>
                    <span class="hc-badge" [class]="caseBadgeClass(activeCase.caseStatus)">
                      {{ caseStatusIcon(activeCase.caseStatus) }}
                      {{ 'denialCaseStatuses.' + activeCase.caseStatus | translate }}
                    </span>
                  </div>
                  <div class="lifecycle-cell">
                    <span class="lifecycle-label">{{ 'statusAppealLabel' | translate }}</span>
                    <span class="hc-badge hc-badge-neutral">
                      ◆ {{ 'appealStatuses.' + activeCase.appealStatus | translate }}
                    </span>
                  </div>
                  <div class="lifecycle-cell">
                    <span class="lifecycle-label">{{ 'statusPaymentLabel' | translate }}</span>
                    <span
                      class="hc-badge"
                      [class]="
                        activeCase.paymentStatus === 'RECOVERED_FULL'
                          ? 'hc-badge-verified'
                          : 'hc-badge-warn'
                      "
                    >
                      {{ activeCase.paymentStatus === 'RECOVERED_FULL' ? '✓' : '!' }}
                      {{ 'paymentStatuses.' + activeCase.paymentStatus | translate }}
                    </span>
                  </div>
                  <div class="lifecycle-cell">
                    <span class="lifecycle-label">{{ 'statusOutcomeLabel' | translate }}</span>
                    <span
                      class="hc-badge"
                      [class]="
                        activeCase.recoveryOutcome === 'RECOVERED_FULL'
                          ? 'hc-badge-verified'
                          : 'hc-badge-neutral'
                      "
                    >
                      {{ activeCase.recoveryOutcome === 'RECOVERED_FULL' ? '✓' : '●' }}
                      {{ 'recoveryOutcomes.' + activeCase.recoveryOutcome | translate }}
                    </span>
                  </div>
                </div>

                <!-- Financial Bar (Exact Integer XOF, separating Estimates from Confirmed Recoveries) -->
                <div class="finance-strip">
                  <div class="finance-box">
                    <div class="hc-muted">{{ 'kpiSubmittedVolume' | translate }}</div>
                    <div class="hc-amount finance-amount-lg">
                      {{ activeCase.submittedAmountXof.toLocaleString('fr-SN') }} XOF
                    </div>
                    <span class="hc-badge hc-badge-verified hc-mt-xs">
                      ✓ {{ 'qualityFlags.HUMAN_VERIFIED' | translate }}
                    </span>
                  </div>
                  <div class="finance-box">
                    <div class="hc-muted">{{ 'denialDeniedAmount' | translate }}</div>
                    <div class="hc-amount finance-amount-danger">
                      {{ activeCase.deniedAmountXof.toLocaleString('fr-SN') }} XOF
                    </div>
                    <span class="hc-badge hc-badge-danger hc-mt-xs">
                      ! {{ activeCase.sourceReasonCode }}
                    </span>
                  </div>
                  <div class="finance-box">
                    <div class="hc-muted">{{ 'denialEstimatedAmount' | translate }}</div>
                    <div class="hc-amount finance-amount-lg">
                      {{ activeCase.estimatedRecoverableXof.toLocaleString('fr-SN') }} XOF
                    </div>
                    <span class="hc-badge hc-badge-warn hc-mt-xs">
                      ◆ {{ 'kpiEstimatedRecoverable' | translate }}
                    </span>
                  </div>
                  <div class="finance-box">
                    <div class="hc-muted">{{ 'recoveredAmountLabel' | translate }}</div>
                    <div class="hc-amount finance-amount-success">
                      {{ activeCase.confirmedRecoveredXof.toLocaleString('fr-SN') }} XOF
                    </div>
                    <span class="hc-badge hc-badge-verified hc-mt-xs">
                      ✓ {{ 'kpiRecovered' | translate }}
                    </span>
                  </div>
                </div>
              </section>

              <!-- Verbatim Original Rejection vs Normalized Reason & Sourced Payer Rule -->
              <section class="card">
                <div class="hc-grid-2">
                  <div>
                    <h3>{{ 'originalRejectionBoxTitle' | translate }}</h3>
                    <p class="hc-muted hc-mb-sm">
                      {{ 'denialOriginalWording' | translate }}
                    </p>
                    <div class="verbatim-box" lang="fr">
                      {{ activeCase.originalReasonText }}
                    </div>
                  </div>
                  <div>
                    <div class="hc-row-between">
                      <h3>{{ 'normalizedReasonBoxTitle' | translate }}</h3>
                      <span class="hc-badge hc-badge-ai">
                        ✦ {{ 'aiAssistiveBadge' | translate }} ({{ activeCase.aiConfidencePct }}%)
                      </span>
                    </div>
                    <div class="normalized-box">
                      <strong>
                        {{
                          isFrench() ? activeCase.normalizedLabelFr : activeCase.normalizedLabelEn
                        }}
                      </strong>
                      <p class="hc-mt-xs hc-text-sm">
                        {{ isFrench() ? activeCase.aiRationaleFr : activeCase.aiRationaleEn }}
                      </p>
                      <div class="hc-mt-sm hc-row-wrap">
                        <span class="hc-badge hc-badge-brand">
                          § {{ activeCase.ruleSourceCitation }}
                        </span>
                        @if (activeCase.deadlineDate) {
                          <span class="hc-badge hc-badge-verified">
                            ✓ {{ 'deadlineBoxTitle' | translate }}: {{ activeCase.deadlineDate }}
                          </span>
                        } @else {
                          <span class="hc-badge hc-badge-neutral">
                            ⏱ {{ 'deadlineBases.UNKNOWN' | translate }}
                          </span>
                        }
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <!-- Supporting Evidence & Recovery Checklist -->
              <section class="hc-grid-2">
                <article class="card">
                  <div class="hc-row-between hc-mb-md">
                    <h2>{{ 'evidenceBoxTitle' | translate }}</h2>
                    <span class="hc-badge hc-badge-warn">
                      {{
                        isFrench() ? activeCase.aiMissingEvidenceFr : activeCase.aiMissingEvidenceEn
                      }}
                    </span>
                  </div>
                  <div class="evidence-list">
                    @for (ev of activeCase.evidence; track ev.id) {
                      <div class="evidence-card">
                        <div class="hc-row-between">
                          <strong class="hc-mono">{{ ev.documentName }}</strong>
                          @if (ev.provenance === 'HUMAN_VERIFIED') {
                            <span class="hc-badge hc-badge-verified">
                              ✓ {{ 'qualityFlags.HUMAN_VERIFIED' | translate }}
                            </span>
                          } @else {
                            <span class="hc-badge hc-badge-ai">
                              ✦ {{ 'qualityFlags.EXTRACTED' | translate }} ({{ ev.confidencePct }}%)
                            </span>
                          }
                        </div>
                        <div class="hc-muted hc-mono">{{ ev.pageLocator }}</div>
                        <p class="hc-text-sm hc-m-0">{{ ev.extractedSnippet }}</p>
                        @if (ev.provenance !== 'HUMAN_VERIFIED' && api.claimWrite()) {
                          <div>
                            <button
                              type="button"
                              class="hc-btn hc-btn-secondary hc-btn-sm"
                              (click)="verifyEvidenceItem(activeCase.id, ev.id)"
                            >
                              ✓ {{ 'verifyEvidenceAction' | translate }}
                            </button>
                          </div>
                        }
                      </div>
                    }
                  </div>
                </article>

                <article class="card">
                  <h2>{{ 'checklistBoxTitle' | translate }}</h2>
                  <div class="hc-mt-sm">
                    @for (chk of activeCase.checklist; track chk.id) {
                      <div class="checklist-row">
                        <label class="checklist-toggle">
                          <input
                            type="checkbox"
                            [checked]="chk.done"
                            (change)="workspace.toggleChecklistItem(activeCase.id, chk.id)"
                          />
                          <span>{{ 'workflowSteps.' + chk.stepKey | translate }}</span>
                        </label>
                        <span
                          class="hc-badge"
                          [class]="chk.done ? 'hc-badge-verified' : 'hc-badge-neutral'"
                        >
                          {{ chk.done ? '✓ ' + ('yes' | translate) : ('no' | translate) }}
                        </span>
                      </div>
                    }
                  </div>
                </article>
              </section>

              <!-- Bilingual Appeal Editor & Human Approval Gate -->
              <section class="appeal-box">
                <div class="hc-page-header hc-mb-md">
                  <div>
                    <h2>{{ 'appealEditorTitle' | translate }}</h2>
                    <p class="hc-muted">{{ 'aiStatusOnline' | translate }}</p>
                  </div>
                  <div class="hc-row-wrap">
                    <span class="hc-badge hc-badge-ai">
                      ✦ {{ 'aiAssistiveBadge' | translate }}
                    </span>
                    <button
                      type="button"
                      class="hc-btn hc-btn-ai hc-btn-sm"
                      (click)="generateAiDraft(activeCase.id)"
                    >
                      ✦ {{ 'generateAiAppealButton' | translate }}
                    </button>
                  </div>
                </div>

                <form [formGroup]="appealForm" (ngSubmit)="saveAppeal(activeCase.id)">
                  <div class="hc-grid-2">
                    <label>
                      {{ 'appealLanguageLabel' | translate }}
                      <select formControlName="locale">
                        <option value="fr-SN">{{ 'french' | translate }} (fr-SN)</option>
                        <option value="en">{{ 'english' | translate }} (en)</option>
                      </select>
                    </label>
                    <label>
                      {{ 'appealSubjectLabel' | translate }}
                      <input formControlName="subject" maxlength="200" />
                    </label>
                  </div>
                  <label>
                    {{ 'appealBodyLabel' | translate }}
                    <textarea rows="8" formControlName="body"></textarea>
                  </label>

                  <div class="actions">
                    <button type="submit" class="secondary">
                      {{ 'saveAppealDraftButton' | translate }}
                    </button>
                    <button
                      type="button"
                      class="secondary"
                      (click)="submitForApproval(activeCase.id)"
                    >
                      {{ 'submitForApprovalButton' | translate }}
                    </button>
                    <button
                      type="button"
                      class="hc-btn hc-btn-success"
                      [disabled]="!canApproveAppeal(activeCase)"
                      [title]="'appealPlaceholderWarning' | translate"
                      (click)="approveAppeal(activeCase.id)"
                    >
                      ✓ {{ 'approveAppealButton' | translate }}
                    </button>
                    @if (api.claimManage() && activeCase.appealStatus === 'AWAITING_APPROVAL') {
                      <button
                        type="button"
                        class="secondary"
                        (click)="workspace.returnAppealForRevision(activeCase.id)"
                      >
                        {{ 'rejectAppealButton' | translate }}
                      </button>
                    }
                  </div>
                  @if (!canApproveAppeal(activeCase)) {
                    <p class="hc-muted hc-mt-sm">ⓘ {{ 'appealPlaceholderWarning' | translate }}</p>
                  }
                </form>
              </section>

              <!-- Record Actual External Submission & Confirmed Payer Settlement -->
              <section class="hc-grid-2">
                <article class="card">
                  <h2>{{ 'recordAppealSubmissionTitle' | translate }}</h2>
                  <form
                    [formGroup]="submissionForm"
                    (ngSubmit)="recordExternalSubmission(activeCase.id)"
                  >
                    <label>
                      {{ 'submissionChannel' | translate }}
                      <select formControlName="channelCode">
                        <option value="PORTAIL_IPM">PORTAIL_IPM</option>
                        <option value="COURRIER">COURRIER</option>
                        <option value="EMAIL">EMAIL</option>
                        <option value="DEPOT_PHYSIQUE">DEPOT_PHYSIQUE</option>
                      </select>
                    </label>
                    <label>
                      {{ 'submissionExternalRef' | translate }}
                      <input
                        formControlName="externalRef"
                        placeholder="BORD-REC-2026-95"
                        maxlength="80"
                      />
                    </label>
                    <label>
                      {{ 'submissionOccurredOn' | translate }}
                      <input type="date" formControlName="occurredOn" />
                    </label>
                    <button type="submit" [disabled]="activeCase.appealStatus !== 'APPROVED'">
                      {{ 'recordAppealSubmissionButton' | translate }}
                    </button>
                  </form>
                </article>

                <article class="card">
                  <h2>{{ 'recordRecoveryPaymentTitle' | translate }}</h2>
                  <form
                    [formGroup]="recoveryForm"
                    (ngSubmit)="recordConfirmedRecovery(activeCase.id)"
                  >
                    <label>
                      {{ 'recoveredAmountLabel' | translate }} (Max:
                      {{ activeCase.deniedAmountXof.toLocaleString('fr-SN') }} XOF)
                      <input
                        type="number"
                        formControlName="recoveredAmountXof"
                        min="0"
                        [max]="activeCase.deniedAmountXof"
                        step="1"
                      />
                    </label>
                    <label class="checklist-toggle hc-mt-xs">
                      <input type="checkbox" formControlName="writeOffRemainder" />
                      <span>{{ 'writeOffRemainderLabel' | translate }}</span>
                    </label>
                    <button type="submit" [disabled]="!api.claimManage()">
                      {{ 'recordRecoveryPaymentButton' | translate }}
                    </button>
                  </form>
                </article>
              </section>

              <!-- Immutable Event History -->
              <section class="card">
                <h2>{{ 'caseTimelineTitle' | translate }}</h2>
                <ul class="timeline-list">
                  @for (ev of activeCase.timeline; track ev.id) {
                    <li class="timeline-item">
                      <div>
                        <strong>{{ isFrench() ? ev.summaryFr : ev.summaryEn }}</strong>
                        <div class="hc-muted hc-mono">{{ ev.actor }} · {{ ev.occurredAt }}</div>
                      </div>
                      <span
                        class="hc-badge"
                        [class]="
                          ev.provenance === 'HUMAN_APPROVED' || ev.provenance === 'VERIFIED_RECORD'
                            ? 'hc-badge-verified'
                            : 'hc-badge-ai'
                        "
                      >
                        {{
                          ev.provenance === 'HUMAN_APPROVED'
                            ? '✓ ' + ('qualityFlags.HUMAN_VERIFIED' | translate)
                            : ev.provenance === 'VERIFIED_RECORD'
                              ? '✓ ' + ('kpiRecovered' | translate)
                              : '✦ ' + ('aiAssistiveBadge' | translate)
                        }}
                      </span>
                    </li>
                  }
                </ul>
              </section>
            </div>
          }
        </div>
      }
    </div>
  `,
})
export class DenialsPage {
  readonly api = inject(Api);
  readonly language = inject(Language);
  readonly workspace = inject(RecoveryWorkspaceService);
  private readonly fb = inject(FormBuilder);
  private readonly destroy = inject(DestroyRef);

  readonly showCreateForm = signal(false);
  readonly statusFilter = signal<'ALL' | DenialCaseStatusKey>('ALL');
  readonly selectedCaseId = signal<string>('demo-case-10472');
  readonly feedbackMessage = signal('');
  readonly errorMessage = signal('');

  readonly liveClaims = signal<ClaimSummary[]>([]);
  readonly livePayers = signal<Payer[]>([]);
  readonly liveCategories = signal<DenialCategory[]>([]);

  readonly rejectionForm = this.fb.nonNullable.group({
    claimExternalRef: ['CLM-2026-10485', Validators.required],
    pseudonymousPatientRef: ['PAT-SN-9104', Validators.required],
    payerCode: ['IPM_DAKAR_01', Validators.required],
    payerType: ['IPM', Validators.required],
    sourceReasonCode: ['REJ-IPM-12', Validators.required],
    noticeDate: ['2026-02-12', Validators.required],
    deniedAmountXof: [125000, [Validators.required, Validators.min(1)]],
    estimatedRecoverableXof: [125000, [Validators.required, Validators.min(0)]],
    originalReasonText: [
      'REJ-IPM-12 : Ordonnance médicale initiale non jointe à la feuille de soins.',
      Validators.required,
    ],
  });

  readonly appealForm = this.fb.nonNullable.group({
    locale: ['fr-SN' as 'fr-SN' | 'en', Validators.required],
    subject: ['', Validators.required],
    body: ['', Validators.required],
  });

  readonly submissionForm = this.fb.nonNullable.group({
    channelCode: ['PORTAIL_IPM', Validators.required],
    externalRef: ['BORD-REC-2026-99', Validators.required],
    occurredOn: ['2026-02-14', Validators.required],
  });

  readonly recoveryForm = this.fb.nonNullable.group({
    recoveredAmountXof: [185000, [Validators.required, Validators.min(0)]],
    writeOffRemainder: [false],
  });

  readonly filteredCases = computed(() => {
    const filter = this.statusFilter();
    const q = this.workspace.searchQuery().trim().toLowerCase();
    return this.workspace.visibleCases().filter((c) => {
      if (filter !== 'ALL' && c.caseStatus !== filter) return false;
      if (!q) return true;
      return (
        c.claimExternalRef.toLowerCase().includes(q) ||
        c.pseudonymousPatientRef.toLowerCase().includes(q) ||
        c.payerCode.toLowerCase().includes(q) ||
        c.sourceReasonCode.toLowerCase().includes(q) ||
        c.originalReasonText.toLowerCase().includes(q)
      );
    });
  });

  readonly selectedCase = computed(() => {
    const list = this.filteredCases();
    const id = this.selectedCaseId();
    return list.find((c) => c.id === id) ?? list[0] ?? null;
  });

  constructor() {
    const initial = this.selectedCase();
    if (initial) {
      this.syncFormsToCase(initial);
    }
    if (this.api.claimRead() && this.api.payerRead()) {
      forkJoin({
        claims: this.api.get<Page<ClaimSummary>>('/claims?page=0&size=50'),
        payers: this.api.get<Page<Payer>>('/payers?page=0&size=100'),
        categories: this.api.get<Page<DenialCategory>>('/denial-categories?page=0&size=100'),
      })
        .pipe(takeUntilDestroyed(this.destroy))
        .subscribe({
          next: ({ claims, payers, categories }) => {
            this.liveClaims.set(claims.items);
            this.livePayers.set(payers.items);
            this.liveCategories.set(categories.items);
          },
          error: () => {},
        });
    }
  }

  isFrench(): boolean {
    return (this.language.translate.getCurrentLang() ?? 'fr-SN').startsWith('fr');
  }

  selectCase(item: DenialRecoveryCase) {
    this.selectedCaseId.set(item.id);
    this.feedbackMessage.set('');
    this.errorMessage.set('');
    this.syncFormsToCase(item);
  }

  private syncFormsToCase(item: DenialRecoveryCase) {
    this.appealForm.reset({
      locale: item.appealLocale,
      subject: item.appealSubject,
      body: item.appealBody,
    });
    this.recoveryForm.reset({
      recoveredAmountXof: item.deniedAmountXof,
      writeOffRemainder: false,
    });
  }

  caseBadgeClass(status: DenialCaseStatusKey): string {
    if (status === 'RECOVERED_FULL') return 'hc-badge-verified';
    if (status === 'ACTION_REQUIRED' || status === 'NEW') return 'hc-badge-danger';
    if (status === 'AWAITING_APPROVAL' || status === 'APPEAL_DRAFT') return 'hc-badge-warn';
    return 'hc-badge-brand';
  }

  caseStatusIcon(status: DenialCaseStatusKey): string {
    if (status === 'RECOVERED_FULL') return '✓';
    if (status === 'ACTION_REQUIRED' || status === 'NEW') return '!';
    return '◆';
  }

  submitNewRejection() {
    if (this.rejectionForm.invalid) {
      this.errorMessage.set('validation');
      return;
    }
    const raw = this.rejectionForm.getRawValue();
    const created = this.workspace.createDenialCase({
      claimExternalRef: raw.claimExternalRef,
      pseudonymousPatientRef: raw.pseudonymousPatientRef,
      payerCode: raw.payerCode,
      payerType: raw.payerType,
      payerOriginalName: raw.payerCode,
      sourceReasonCode: raw.sourceReasonCode,
      originalReasonText: raw.originalReasonText,
      normalizedCategoryKey: 'missing-attachment',
      normalizedLabelFr: 'Pièce justificative ou ordonnance absente',
      normalizedLabelEn: 'Missing supporting attachment or prescription',
      noticeDate: raw.noticeDate,
      deniedAmountXof: Number(raw.deniedAmountXof),
      estimatedRecoverableXof: Number(raw.estimatedRecoverableXof),
      hasApprovedRule: false,
    });
    this.showCreateForm.set(false);
    this.selectCase(created);
    this.feedbackMessage.set('saved');
  }

  verifyEvidenceItem(caseId: string, evidenceId: string) {
    this.workspace.verifyEvidence(caseId, evidenceId);
    this.feedbackMessage.set('saved');
  }

  generateAiDraft(caseId: string) {
    const locale = this.appealForm.controls.locale.value;
    this.workspace.generateAiAppealDraft(caseId, locale);
    const updated = this.workspace.casesState().find((c) => c.id === caseId);
    if (updated) {
      this.syncFormsToCase(updated);
    }
    this.feedbackMessage.set('saved');
  }

  saveAppeal(caseId: string) {
    if (this.appealForm.invalid) {
      this.errorMessage.set('validation');
      return;
    }
    this.errorMessage.set('');
    this.workspace.saveAppealDraft(caseId, this.appealForm.getRawValue());
    this.feedbackMessage.set('saved');
  }

  submitForApproval(caseId: string) {
    this.saveAppeal(caseId);
    this.workspace.submitAppealForApproval(caseId);
    this.feedbackMessage.set('saved');
  }

  canApproveAppeal(item: DenialRecoveryCase): boolean {
    const body = this.appealForm.controls.body.value || item.appealBody;
    return this.api.claimManage() && body.trim().length > 0 && !body.includes('[PLACEHOLDER');
  }

  approveAppeal(caseId: string) {
    this.saveAppeal(caseId);
    const ok = this.workspace.approveAppeal(caseId);
    if (!ok) {
      this.errorMessage.set('appealPlaceholderWarning');
      return;
    }
    this.errorMessage.set('');
    this.feedbackMessage.set('saved');
  }

  recordExternalSubmission(caseId: string) {
    if (this.submissionForm.invalid) {
      this.errorMessage.set('validation');
      return;
    }
    const ok = this.workspace.recordAppealSubmission(caseId, this.submissionForm.getRawValue());
    if (!ok) {
      this.errorMessage.set('errors.FORBIDDEN');
      return;
    }
    this.feedbackMessage.set('saved');
  }

  recordConfirmedRecovery(caseId: string) {
    if (this.recoveryForm.invalid) {
      this.errorMessage.set('validation');
      return;
    }
    const raw = this.recoveryForm.getRawValue();
    const ok = this.workspace.recordRecoveryPayment(
      caseId,
      Number(raw.recoveredAmountXof),
      raw.writeOffRemainder,
    );
    if (!ok) {
      this.errorMessage.set('validation');
      return;
    }
    this.errorMessage.set('');
    this.feedbackMessage.set('saved');
  }
}
