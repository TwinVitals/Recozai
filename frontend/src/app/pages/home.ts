import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap } from 'rxjs';
import { Api, ClaimSummary, Page, Profile, errorKey } from '../core/api.service';
import { Language } from '../core/language.service';
import { RecoveryWorkspaceService } from '../core/recovery-workspace.service';

@Component({
  imports: [ReactiveFormsModule, RouterLink, TranslatePipe],
  template: `
    <div class="dash-stack">
      <section class="dash-hero">
        <div>
          <div class="dash-greeting">
            {{ 'greetingPrefix' | translate }}, {{ api.user()?.login || 'Clinique Dakar' }} !
          </div>
          <h1>{{ 'welcome' | translate }}</h1>
          <p class="hc-muted">{{ 'dashboardSubtitle' | translate }}</p>
        </div>
        <div class="dash-hero-actions">
          @if (workspace.showSyntheticDemo()) {
            <span class="hc-badge hc-badge-warn">◆ {{ 'syntheticDemoBadge' | translate }}</span>
          }
          @if (api.claimRead()) {
            <a class="hc-btn hc-btn-secondary" routerLink="/claims">
              + {{ 'viewAllClaims' | translate }}
            </a>
            <a class="hc-btn" routerLink="/denials"> {{ 'openDenialQueue' | translate }} → </a>
          }
        </div>
      </section>

      <!-- Top 4 KPI Cards matching visual reference -->
      <section class="hc-grid-4" [attr.aria-label]="'home' | translate">
        <article class="kpi-card">
          <div class="kpi-top">
            <span class="kpi-label">{{ 'kpiTotalClaims' | translate }}</span>
            <span class="hc-badge hc-badge-brand">▲ +12.5%</span>
          </div>
          <div class="kpi-value-row">
            <span class="kpi-value">{{ kpiStats().totalClaimsCount }}</span>
            <svg width="78" height="28" viewBox="0 0 78 28" aria-hidden="true">
              <polyline
                fill="none"
                stroke="#2563eb"
                stroke-width="2.2"
                points="2,22 18,18 34,20 50,12 64,14 76,6"
              />
            </svg>
          </div>
          <div class="kpi-sub">
            <span>{{ kpiStats().totalClaimsXof }} XOF</span>
            <span class="hc-muted">{{ 'kpiSubmittedVolume' | translate }}</span>
          </div>
        </article>

        <article class="kpi-card">
          <div class="kpi-top">
            <span class="kpi-label">{{ 'kpiDenials' | translate }}</span>
            <span class="hc-badge hc-badge-danger">! {{ kpiStats().openDenialsCount }}</span>
          </div>
          <div class="kpi-value-row">
            <span class="kpi-value">{{ kpiStats().totalDenialsCount }}</span>
            <svg width="78" height="28" viewBox="0 0 78 28" aria-hidden="true">
              <polyline
                fill="none"
                stroke="#dc2626"
                stroke-width="2.2"
                points="2,10 18,14 34,12 50,19 64,16 76,21"
              />
            </svg>
          </div>
          <div class="kpi-sub">
            <span>{{ kpiStats().deniedAmountXof }} XOF</span>
            <span class="hc-muted">{{ 'kpiDisputedVolume' | translate }}</span>
          </div>
        </article>

        <article class="kpi-card">
          <div class="kpi-top">
            <span class="kpi-label">{{ 'kpiRecovered' | translate }}</span>
            <span class="hc-badge hc-badge-verified">✓ +18.3%</span>
          </div>
          <div class="kpi-value-row">
            <span class="kpi-value">{{ kpiStats().recoveredAmountXof }} XOF</span>
          </div>
          <div class="kpi-sub">
            <span>{{ 'kpiApprovedRules' | translate }}</span>
            <span class="hc-muted">{{ 'last30Days' | translate }}</span>
          </div>
        </article>

        <article class="kpi-card">
          <div class="kpi-top">
            <span class="kpi-label">{{ 'kpiRecoveryRate' | translate }}</span>
            <span class="hc-badge hc-badge-verified">✓ +22.6%</span>
          </div>
          <div class="kpi-value-row">
            <span class="kpi-value">{{ kpiStats().recoveryRatePct }}%</span>
            <svg width="46" height="46" viewBox="0 0 36 36" aria-hidden="true">
              <path
                d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                fill="none"
                stroke="#e2e8f0"
                stroke-width="3.8"
              />
              <path
                d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                fill="none"
                stroke="#059669"
                stroke-width="3.8"
                [attr.stroke-dasharray]="kpiStats().recoveryRatePct + ', 100'"
              />
            </svg>
          </div>
          <div class="kpi-sub">
            <span class="hc-muted">{{ 'kpiEstimatedRecoverable' | translate }}</span>
          </div>
        </article>
      </section>

      <!-- Middle Row: Recovery Performance Chart + By Payer Donut + AI Recovery Assistant -->
      <section class="hc-grid-3">
        <article class="hc-card">
          <div class="chart-header">
            <div>
              <h2>{{ 'chartRecoveryPerformance' | translate }}</h2>
              <p class="hc-muted">{{ 'last30Days' | translate }}</p>
            </div>
            <div
              class="timeframe-group"
              role="group"
              [attr.aria-label]="'chartRecoveryPerformance' | translate"
            >
              <button
                type="button"
                class="timeframe-btn"
                [class.active]="workspace.selectedTimeframe() === '7D'"
                (click)="workspace.selectedTimeframe.set('7D')"
              >
                {{ 'period7d' | translate }}
              </button>
              <button
                type="button"
                class="timeframe-btn"
                [class.active]="workspace.selectedTimeframe() === '30D'"
                (click)="workspace.selectedTimeframe.set('30D')"
              >
                {{ 'period30d' | translate }}
              </button>
              <button
                type="button"
                class="timeframe-btn"
                [class.active]="workspace.selectedTimeframe() === '90D'"
                (click)="workspace.selectedTimeframe.set('90D')"
              >
                {{ 'period90d' | translate }}
              </button>
            </div>
          </div>

          <svg
            viewBox="0 0 360 150"
            width="100%"
            height="150"
            role="img"
            [attr.aria-label]="'chartRecoveryPerformance' | translate"
          >
            <line x1="28" y1="25" x2="345" y2="25" stroke="#f1f5f9" stroke-width="1" />
            <line x1="28" y1="65" x2="345" y2="65" stroke="#f1f5f9" stroke-width="1" />
            <line x1="28" y1="105" x2="345" y2="105" stroke="#f1f5f9" stroke-width="1" />
            <line x1="28" y1="130" x2="345" y2="130" stroke="#e2e8f0" stroke-width="1" />
            <polyline
              fill="none"
              stroke="#2563eb"
              stroke-width="2.5"
              [attr.points]="chartPoints().submitted"
            />
            <polyline
              fill="none"
              stroke="#10b981"
              stroke-width="2.5"
              [attr.points]="chartPoints().recovered"
            />
          </svg>
          <div class="kpi-sub">
            <span
              ><span class="legend-dot dot-blue"></span
              >{{ 'chartRecoveryRateSeries' | translate }}</span
            >
            <span
              ><span class="legend-dot dot-emerald"></span
              >{{ 'chartTargetSeries' | translate }}</span
            >
          </div>
        </article>

        <article class="hc-card">
          <div class="chart-header">
            <div>
              <h2>{{ 'chartByPayer' | translate }}</h2>
              <p class="hc-muted">{{ 'last30Days' | translate }}</p>
            </div>
          </div>
          <div class="donut-wrap">
            <svg width="112" height="112" viewBox="0 0 36 36" aria-hidden="true">
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#2563eb"
                stroke-width="4.5"
                stroke-dasharray="39 61"
                stroke-dashoffset="25"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#0ea5e9"
                stroke-width="4.5"
                stroke-dasharray="28 72"
                stroke-dashoffset="86"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#10b981"
                stroke-width="4.5"
                stroke-dasharray="18 82"
                stroke-dashoffset="58"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#f59e0b"
                stroke-width="4.5"
                stroke-dasharray="15 85"
                stroke-dashoffset="40"
              />
            </svg>
            <ul class="legend-list">
              <li class="legend-item">
                <span><span class="legend-dot dot-blue"></span>CNAM / CMU</span>
                <strong>39%</strong>
              </li>
              <li class="legend-item">
                <span><span class="legend-dot dot-sky"></span>IPM Dakar</span>
                <strong>28%</strong>
              </li>
              <li class="legend-item">
                <span><span class="legend-dot dot-emerald"></span>Mutuelles</span>
                <strong>18%</strong>
              </li>
              <li class="legend-item">
                <span><span class="legend-dot dot-amber"></span>Assurance / Autres</span>
                <strong>15%</strong>
              </li>
            </ul>
          </div>
        </article>

        <!-- AI Recovery Assistant Panel -->
        <aside class="ai-panel" [attr.aria-label]="'aiAssistantTitle' | translate">
          <div>
            <div class="kpi-top hc-mb-sm">
              <h2>✦ {{ 'aiAssistantTitle' | translate }}</h2>
              <span class="hc-badge hc-badge-ai">{{ 'aiStatusOnline' | translate }}</span>
            </div>
            <p class="hc-muted hc-mb-sm">
              {{ 'aiAssistiveBadge' | translate }}
            </p>
            <div class="ai-bubble">
              {{ 'aiAssistantIntro' | translate }}
            </div>
            @if (workspace.aiChatLog().length) {
              <div class="ai-bubble hc-mt-sm">
                <strong>Q: {{ workspace.aiChatLog()[0].question }}</strong>
                <p class="hc-mt-xs">
                  {{
                    (language.translate.getCurrentLang() ?? 'fr-SN').startsWith('fr')
                      ? workspace.aiChatLog()[0].answerFr
                      : workspace.aiChatLog()[0].answerEn
                  }}
                </p>
              </div>
            }
          </div>

          <div>
            <div class="ai-quick-actions">
              <button
                type="button"
                class="hc-btn hc-btn-ai hc-btn-sm"
                (click)="runQuickAiPrompt('aiQuickAnalyze')"
              >
                ✦ {{ 'aiQuickAnalyze' | translate }}
              </button>
              <button
                type="button"
                class="hc-btn hc-btn-ai hc-btn-sm"
                (click)="runQuickAiPrompt('aiQuickAppealFr')"
              >
                ✦ {{ 'aiQuickAppealFr' | translate }}
              </button>
              <button
                type="button"
                class="hc-btn hc-btn-ai hc-btn-sm"
                (click)="runQuickAiPrompt('aiQuickRecommendations')"
              >
                ✦ {{ 'aiQuickRecommendations' | translate }}
              </button>
            </div>
            <div class="ai-prompt-row">
              <input
                type="text"
                #aiInput
                [placeholder]="'aiQuestionPlaceholder' | translate"
                [attr.aria-label]="'aiQuestionPlaceholder' | translate"
                (keydown.enter)="askAi(aiInput)"
              />
              <button type="button" class="hc-btn hc-btn-sm" (click)="askAi(aiInput)">
                {{ 'aiAskButton' | translate }}
              </button>
            </div>
          </div>
        </aside>
      </section>

      <!-- Bottom Row: Denial Breakdown + Recent Claims Table -->
      <section class="governance-grid">
        <article class="hc-card">
          <div class="chart-header">
            <div>
              <h2>{{ 'chartDenialBreakdown' | translate }}</h2>
              <p class="hc-muted">{{ 'last30Days' | translate }}</p>
            </div>
          </div>
          <div class="donut-wrap">
            <svg width="108" height="108" viewBox="0 0 36 36" aria-hidden="true">
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#ef4444"
                stroke-width="4.5"
                stroke-dasharray="45 55"
                stroke-dashoffset="25"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#f59e0b"
                stroke-width="4.5"
                stroke-dasharray="25 75"
                stroke-dashoffset="80"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#3b82f6"
                stroke-width="4.5"
                stroke-dasharray="18 82"
                stroke-dashoffset="55"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="transparent"
                stroke="#8b5cf6"
                stroke-width="4.5"
                stroke-dasharray="12 88"
                stroke-dashoffset="37"
              />
            </svg>
            <ul class="legend-list">
              <li class="legend-item">
                <span
                  ><span class="legend-dot dot-red"></span
                  >{{ 'workflowSteps.COLLECT_SUPPORTING_DOCUMENTS' | translate }}</span
                >
                <strong>45%</strong>
              </li>
              <li class="legend-item">
                <span
                  ><span class="legend-dot dot-amber"></span
                  >{{ 'workflowSteps.VERIFY_SOURCE_WORDING' | translate }}</span
                >
                <strong>25%</strong>
              </li>
              <li class="legend-item">
                <span
                  ><span class="legend-dot dot-indigo"></span
                  >{{ 'workflowSteps.RECONCILE_EXPECTED_AMOUNT' | translate }}</span
                >
                <strong>18%</strong>
              </li>
              <li class="legend-item">
                <span
                  ><span class="legend-dot dot-purple"></span
                  >{{ 'workflowSteps.MANAGER_ESCALATION_REVIEW' | translate }}</span
                >
                <strong>12%</strong>
              </li>
            </ul>
          </div>
        </article>

        <article class="hc-card">
          <div class="chart-header">
            <div>
              <h2>{{ 'recentClaimsTitle' | translate }}</h2>
              <p class="hc-muted">{{ 'foundationDetail' | translate }}</p>
            </div>
            @if (api.claimRead()) {
              <a class="hc-btn hc-btn-secondary hc-btn-sm" routerLink="/denials">
                {{ 'openDenialQueue' | translate }}
              </a>
            }
          </div>

          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{{ 'colReference' | translate }}</th>
                  <th>{{ 'colPatient' | translate }}</th>
                  <th>{{ 'colPayer' | translate }}</th>
                  <th>{{ 'colAmount' | translate }}</th>
                  <th>{{ 'colStatus' | translate }}</th>
                  <th>{{ 'colAction' | translate }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of recentRows(); track row.id) {
                  <tr>
                    <td class="hc-mono">
                      <strong>{{ row.claimRef }}</strong>
                      @if (row.isSynthetic) {
                        <span class="hc-badge hc-badge-warn">Demo</span>
                      }
                    </td>
                    <td class="hc-mono">{{ row.patientRef }}</td>
                    <td>{{ row.payerCode }}</td>
                    <td class="hc-amount">{{ row.amountXof.toLocaleString('fr-SN') }} XOF</td>
                    <td>
                      <span class="hc-badge" [class]="row.badgeClass">
                        {{ row.statusIcon }} {{ row.statusKey | translate }}
                      </span>
                    </td>
                    <td>
                      <a class="hc-btn hc-btn-secondary hc-btn-sm" [routerLink]="row.link">
                        {{ 'viewAction' | translate }}
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </article>
      </section>

      <!-- Clinic Context, Roles & Membership Invitation Section (Preserved from foundation) -->
      <section class="card">
        <div class="governance-grid">
          <div>
            <h2>{{ 'ownRoles' | translate }}</h2>
            <ul>
              @for (role of api.user()?.roles; track role) {
                <li>{{ 'roles.' + role | translate }}</li>
              }
            </ul>
            @if (api.user()?.memberships?.length) {
              <form [formGroup]="selection" (ngSubmit)="select()">
                <label
                  >{{ 'selectClinic' | translate
                  }}<select formControlName="membershipId">
                    @for (member of api.user()?.memberships; track member.id) {
                      @if (member.active) {
                        <option [value]="member.id">{{ member.clinicId }}</option>
                      }
                    }
                  </select></label
                ><button [disabled]="busy()">{{ 'select' | translate }}</button>
              </form>
            } @else {
              <p>{{ 'noMembership' | translate }}</p>
            }
          </div>

          <div>
            <h2>{{ 'accept' | translate }}</h2>
            <form [formGroup]="invitation" (ngSubmit)="accept()">
              <label
                >{{ 'invitationId' | translate
                }}<input formControlName="id" maxlength="36" autocomplete="off"
              /></label>
              <label
                >{{ 'token' | translate
                }}<input formControlName="token" autocomplete="off" maxlength="100"
              /></label>
              <button [disabled]="busy()">{{ 'accept' | translate }}</button>
            </form>
          </div>
        </div>
        @if (error()) {
          <p class="error" role="alert">{{ error() | translate }}</p>
        }
      </section>
    </div>
  `,
})
export class Home {
  readonly api = inject(Api);
  readonly language = inject(Language);
  readonly workspace = inject(RecoveryWorkspaceService);
  readonly liveClaims = signal<ClaimSummary[]>([]);
  readonly selection = inject(FormBuilder).nonNullable.group({
    membershipId: [this.api.user()?.membershipId ?? '', Validators.required],
  });
  readonly invitation = inject(FormBuilder).nonNullable.group({
    id: ['', [Validators.required, Validators.pattern('[0-9a-fA-F-]{36}')]],
    token: ['', Validators.required],
  });
  readonly error = signal('');
  readonly busy = signal(false);
  private readonly destroy = inject(DestroyRef);

  readonly kpiStats = computed(() => {
    const cases = this.workspace.visibleCases();
    const live = this.liveClaims();
    if (this.workspace.showSyntheticDemo()) {
      return {
        totalClaimsCount: (1280 + live.length).toLocaleString('fr-SN'),
        totalClaimsXof: '28,4M',
        totalDenialsCount: (242 + cases.length).toString(),
        openDenialsCount: cases.filter((c) => c.recoveryOutcome === 'PENDING').length,
        deniedAmountXof: '6,2M',
        recoveredAmountXof: '4,4M',
        recoveryRatePct: 71,
      };
    }
    const deniedSum = cases.reduce((acc, c) => acc + c.deniedAmountXof, 0);
    const recoveredSum = cases.reduce((acc, c) => acc + c.confirmedRecoveredXof, 0);
    const rate = deniedSum > 0 ? Math.round((recoveredSum / deniedSum) * 100) : 0;
    const liveSum = live.reduce((acc, c) => acc + Number(c.activeSubmittedAmount?.amount ?? 0), 0);
    return {
      totalClaimsCount: live.length.toString(),
      totalClaimsXof: liveSum.toLocaleString('fr-SN'),
      totalDenialsCount: cases.length.toString(),
      openDenialsCount: cases.filter((c) => c.recoveryOutcome === 'PENDING').length,
      deniedAmountXof: deniedSum.toLocaleString('fr-SN'),
      recoveredAmountXof: recoveredSum.toLocaleString('fr-SN'),
      recoveryRatePct: rate,
    };
  });

  readonly chartPoints = computed(() => {
    const tf = this.workspace.selectedTimeframe();
    if (tf === '7D') {
      return {
        submitted: '30,95 90,82 150,76 210,64 270,58 340,42',
        recovered: '30,115 90,104 150,96 210,88 270,74 340,60',
      };
    }
    if (tf === '90D') {
      return {
        submitted: '30,110 90,98 150,80 210,72 270,50 340,30',
        recovered: '30,122 90,112 150,98 210,85 270,68 340,48',
      };
    }
    return {
      submitted: '30,105 90,90 150,84 210,68 270,54 340,36',
      recovered: '30,118 90,108 150,95 210,82 270,70 340,52',
    };
  });

  readonly recentRows = computed(() => {
    const liveRows = this.liveClaims()
      .slice(0, 3)
      .map((c) => ({
        id: c.id,
        claimRef: c.externalReference,
        patientRef: c.pseudonymousPatientRef,
        payerCode: c.payerId.slice(0, 8),
        amountXof: Number(c.activeSubmittedAmount?.amount ?? 0),
        statusKey: `claimStates.${c.state}`,
        statusIcon: c.state === 'SUBMITTED' ? '●' : '◆',
        badgeClass: c.state === 'SUBMITTED' ? 'hc-badge-brand' : 'hc-badge-neutral',
        link: '/claims',
        isSynthetic: false,
      }));
    const demoRows = this.workspace.visibleCases().map((d) => ({
      id: d.id,
      claimRef: d.claimExternalRef,
      patientRef: d.pseudonymousPatientRef,
      payerCode: d.payerCode,
      amountXof: d.deniedAmountXof,
      statusKey: `denialCaseStatuses.${d.caseStatus}`,
      statusIcon:
        d.caseStatus === 'RECOVERED_FULL' ? '✓' : d.caseStatus === 'ACTION_REQUIRED' ? '!' : '◆',
      badgeClass:
        d.caseStatus === 'RECOVERED_FULL'
          ? 'hc-badge-verified'
          : d.caseStatus === 'ACTION_REQUIRED'
            ? 'hc-badge-danger'
            : 'hc-badge-warn',
      link: '/denials',
      isSynthetic: d.isSyntheticDemo,
    }));
    return [...liveRows, ...demoRows].slice(0, 5);
  });

  constructor() {
    if (this.api.claimRead()) {
      this.api
        .get<Page<ClaimSummary>>('/claims?page=0&size=20')
        .pipe(takeUntilDestroyed(this.destroy))
        .subscribe({
          next: (page) => this.liveClaims.set(page.items),
          error: () => {},
        });
    }
  }

  runQuickAiPrompt(actionKey: string) {
    const label = this.language.translate.instant(actionKey);
    this.workspace.askAiAssistant(label);
  }

  askAi(input: HTMLInputElement) {
    if (!input.value.trim()) return;
    this.workspace.askAiAssistant(input.value);
    input.value = '';
  }

  select() {
    if (this.selection.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.api
      .mutate<Profile>('POST', '/session/membership', this.selection.getRawValue())
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (user) => this.api.user.set(user),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
  accept() {
    if (this.invitation.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    const body = this.invitation.getRawValue();
    this.api
      .mutate<void>('POST', `/membership-invitations/${body.id}/acceptance`, { token: body.token })
      .pipe(
        switchMap(() => this.api.refresh()),
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => this.invitation.reset(),
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
