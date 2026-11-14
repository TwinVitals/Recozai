import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin } from 'rxjs';
import { Avatar } from 'primeng/avatar';
import { Badge } from 'primeng/badge';
import { ButtonDirective } from 'primeng/button';
import { Card } from 'primeng/card';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Timeline } from 'primeng/timeline';
import {
  Api,
  ClaimDetail,
  ClaimRevision,
  ClaimState,
  ClaimSubmissionEvent,
  ClaimSummary,
  ClaimTransitionAction,
  DocumentMetadata,
  EvidenceVersion,
  Money,
  OccurrencePrecision,
  Page,
  Payer,
  QualityFlag,
  QuarantineStatus,
  SubmissionEventKind,
  errorKey,
  submissionEventKinds,
} from '../core/api.service';

type PrimeTagSeverity = 'success' | 'secondary' | 'info' | 'warn' | 'danger' | 'contrast';

@Component({
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    Avatar,
    Badge,
    ButtonDirective,
    Card,
    InputText,
    Message,
    TableModule,
    Tag,
    Timeline,
  ],
  template: ` <div class="claims-page hc-prime-claims">
    @if (!api.claimRead()) {
      <p-card styleClass="hc-prime-card">
        <h1>{{ 'claims' | translate }}</h1>
        <p-message severity="error" role="alert">{{ 'errors.FORBIDDEN' | translate }}</p-message>
      </p-card>
    } @else {
      <header class="hc-page-header claims-header hc-prime-hero-header">
        <div class="claims-header-identity">
          <p-avatar label="Rx" shape="circle" size="large" styleClass="hc-prime-avatar-brand" />
          <div>
            <p class="claims-eyebrow">{{ 'claimsWorkspaceEyebrow' | translate }}</p>
            <h1>{{ 'claims' | translate }}</h1>
            <p class="hc-muted">{{ 'claimsWorkspaceSubtitle' | translate }}</p>
          </div>
        </div>
        @if (api.claimWrite()) {
          <button
            pButton
            type="button"
            class="hc-btn hc-prime-cta-btn"
            [attr.aria-expanded]="showCreateForm()"
            (click)="showCreateForm.update((shown) => !shown)"
          >
            <span aria-hidden="true">{{ showCreateForm() ? '−' : '+' }}</span>
            {{ (showCreateForm() ? 'closeClaimForm' : 'newClaim') | translate }}
          </button>
        }
      </header>

      @if (!api.claimWrite()) {
        <p-message severity="info" styleClass="hc-prime-notice" role="status">
          {{ 'claimReadOnlyNotice' | translate }}
        </p-message>
      }
      @if (loading()) {
        <p-message severity="secondary" styleClass="hc-prime-notice" role="status">
          {{ 'loading' | translate }}
        </p-message>
      }
      @if (message()) {
        <p-message severity="success" styleClass="hc-prime-notice" role="status">
          {{ message() | translate }}
        </p-message>
      }
      @if (error()) {
        <p-message severity="error" styleClass="hc-prime-notice" role="alert">
          {{ error() | translate }}
        </p-message>
      }

      <section class="claims-summary" [attr.aria-label]="'claimSummaryTitle' | translate">
        <p-card styleClass="claims-stat hc-prime-stat-card hc-stat-total">
          <div class="hc-stat-top">
            <span>{{ 'claimTotalCount' | translate }}</span>
            <p-avatar label="Σ" shape="circle" styleClass="hc-stat-avatar hc-stat-avatar-teal" />
          </div>
          <strong>{{ totalClaims() }}</strong>
          <small>{{ 'claimTotalHint' | translate }}</small>
        </p-card>
        <p-card styleClass="claims-stat hc-prime-stat-card hc-stat-draft">
          <div class="hc-stat-top">
            <span>{{ 'claimStates.DRAFT' | translate }}</span>
            <p-tag
              severity="secondary"
              [rounded]="true"
              [value]="'claimStates.DRAFT' | translate"
            />
          </div>
          <strong>{{ statusCounts().DRAFT }}</strong>
          <small>{{ 'claimDraftHint' | translate }}</small>
        </p-card>
        <p-card styleClass="claims-stat hc-prime-stat-card hc-stat-registered">
          <div class="hc-stat-top">
            <span>{{ 'claimStates.REGISTERED' | translate }}</span>
            <p-tag
              severity="info"
              [rounded]="true"
              [value]="'claimStates.REGISTERED' | translate"
            />
          </div>
          <strong>{{ statusCounts().REGISTERED }}</strong>
          <small>{{ 'claimRegisteredHint' | translate }}</small>
        </p-card>
        <p-card styleClass="claims-stat hc-prime-stat-card hc-stat-submitted">
          <div class="hc-stat-top">
            <span>{{ 'claimStates.SUBMITTED' | translate }}</span>
            <p-tag
              severity="success"
              [rounded]="true"
              [value]="'claimStates.SUBMITTED' | translate"
            />
          </div>
          <strong>{{ statusCounts().SUBMITTED }}</strong>
          <small>{{ 'claimSubmittedHint' | translate }}</small>
        </p-card>
      </section>

      @if (showCreateForm() && api.claimWrite()) {
        <p-card styleClass="card claim-create-panel hc-prime-card" id="claim-create-form">
          <div class="claim-panel-heading">
            <div class="claim-panel-title-group">
              <p-avatar label="+" shape="circle" styleClass="hc-stat-avatar hc-stat-avatar-teal" />
              <div>
                <h2>{{ 'newClaim' | translate }}</h2>
                <p class="hc-muted">{{ 'newClaimSubtitle' | translate }}</p>
              </div>
            </div>
            <button
              pButton
              type="button"
              severity="secondary"
              [outlined]="true"
              size="small"
              class="secondary hc-btn-sm"
              (click)="showCreateForm.set(false)"
            >
              {{ 'closeClaimForm' | translate }}
            </button>
          </div>
          <form class="claims-entry-form" [formGroup]="draftForm" (ngSubmit)="createDraft()">
            <label>
              {{ 'claimPayer' | translate }}
              <select class="p-inputtext hc-prime-select" formControlName="payerId">
                <option value="">—</option>
                @for (payer of payers(); track payer.id) {
                  @if (payer.active) {
                    <option [value]="payer.id">{{ payer.originalName }} ({{ payer.code }})</option>
                  }
                }
              </select>
            </label>
            <label>
              {{ 'claimExternalRef' | translate }}
              <input pInputText formControlName="externalReference" maxlength="120" />
            </label>
            <label>
              {{ 'claimPatientRef' | translate }}
              <input pInputText formControlName="pseudonymousPatientRef" maxlength="120" />
            </label>
            <label>
              {{ 'claimSourceDate' | translate }}
              <input pInputText type="date" formControlName="sourceDate" />
            </label>
            <label>
              {{ 'claimServiceCode' | translate }}
              <input pInputText formControlName="serviceCode" maxlength="80" />
            </label>
            <label>
              {{ 'claimLineDescription' | translate }}
              <input pInputText formControlName="description" maxlength="240" />
            </label>
            <label>
              {{ 'claimLineAmount' | translate }}
              <input pInputText formControlName="amount" maxlength="40" />
            </label>
            <div class="claims-form-actions">
              <button pButton type="submit" [disabled]="busy()">
                {{ 'createDraftClaim' | translate }}
              </button>
            </div>
          </form>
        </p-card>
      }

      <p-card styleClass="card claims-table-card hc-prime-card">
        <div class="claims-list-heading">
          <div>
            <h2>{{ 'claimListTitle' | translate }}</h2>
            <p class="hc-muted">{{ 'claimListSubtitle' | translate }}</p>
          </div>
          <label class="claims-search">
            <span>{{ 'searchClaimsLabel' | translate }}</span>
            <input
              pInputText
              type="search"
              [value]="claimSearch()"
              [placeholder]="'searchClaimsPlaceholder' | translate"
              (input)="onClaimSearch($event)"
            />
          </label>
        </div>

        <div
          class="claims-filters"
          role="group"
          [attr.aria-label]="'filterClaimsByStatus' | translate"
        >
          <button
            pButton
            type="button"
            severity="secondary"
            [outlined]="statusFilter() !== 'ALL'"
            size="small"
            class="claims-filter"
            [class.active]="statusFilter() === 'ALL'"
            (click)="statusFilter.set('ALL')"
          >
            {{ 'claimFilters.ALL' | translate }}
            <p-badge [value]="claims().length" severity="secondary" />
          </button>
          @for (state of claimStates; track state) {
            <button
              pButton
              type="button"
              severity="secondary"
              [outlined]="statusFilter() !== state"
              size="small"
              class="claims-filter"
              [class.active]="statusFilter() === state"
              (click)="statusFilter.set(state)"
            >
              {{ 'claimStates.' + state | translate }}
              <p-badge [value]="statusCounts()[state]" [severity]="claimStateSeverity(state)" />
            </button>
          }
        </div>

        <div class="claims-table-scroll">
          <p-table
            [value]="filteredClaims()"
            [rowHover]="true"
            styleClass="p-datatable-sm hc-prime-datatable"
            tableStyleClass="claims-table"
          >
            <ng-template #header>
              <tr>
                <th scope="col">{{ 'claimExternalRef' | translate }}</th>
                <th scope="col">{{ 'claimPatientRef' | translate }}</th>
                <th scope="col">{{ 'claimPayer' | translate }}</th>
                <th scope="col">{{ 'claimTableAmount' | translate }}</th>
                <th scope="col">{{ 'claimLastUpdated' | translate }}</th>
                <th scope="col">{{ 'claimStatesLabel' | translate }}</th>
                <th scope="col">
                  <span class="visually-hidden">{{ 'claimTableActions' | translate }}</span>
                </th>
              </tr>
            </ng-template>
            <ng-template #body let-claim>
              <tr [class.is-selected]="selectedClaim()?.id === claim.id">
                <td>
                  <div class="claim-ref-cell">
                    <strong class="claim-reference">{{ claim.externalReference }}</strong>
                    <span class="claim-row-meta">
                      @if (claim.activeRevisionNumber; as revision) {
                        R{{ revision }}
                      } @else {
                        {{ 'claimDraftRevision' | translate }}
                      }
                    </span>
                  </div>
                </td>
                <td>
                  <span class="hc-patient-chip hc-mono">{{ claim.pseudonymousPatientRef }}</span>
                </td>
                <td>
                  <div class="claim-payer-cell">
                    <span>{{ payerName(claim.payerId) ?? ('unknownValue' | translate) }}</span>
                    @if (payerCode(claim.payerId); as code) {
                      <p-tag severity="secondary" [value]="code" />
                    }
                  </div>
                </td>
                <td class="claim-amount">
                  @if (claim.activeSubmittedAmount; as amount) {
                    <span class="hc-amount-pill">{{ amount.amount }} {{ amount.currency }}</span>
                  } @else {
                    <span class="hc-muted">—</span>
                  }
                </td>
                <td>{{ claim.updatedAt.slice(0, 10) }}</td>
                <td>
                  <p-tag
                    [severity]="claimStateSeverity(claim.state)"
                    [rounded]="true"
                    [value]="'claimStates.' + claim.state | translate"
                    [class]="'hc-badge ' + claimStateBadgeClass(claim.state)"
                  />
                </td>
                <td class="claim-action-cell">
                  <button
                    pButton
                    type="button"
                    severity="secondary"
                    [outlined]="selectedClaim()?.id !== claim.id"
                    size="small"
                    class="secondary hc-btn-sm"
                    [disabled]="busy()"
                    (click)="selectClaim(claim.id)"
                  >
                    {{ 'openClaimDetails' | translate }}
                  </button>
                </td>
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td colspan="7" class="claims-empty">
                  {{
                    (claimSearch().trim() || statusFilter() !== 'ALL'
                      ? 'claimSearchEmpty'
                      : 'empty'
                    ) | translate
                  }}
                </td>
              </tr>
            </ng-template>
          </p-table>
        </div>

        <div class="claims-pagination">
          <span class="hc-muted">
            {{ 'claimShowingRange' | translate }} {{ pageFirst() }}–{{ pageLast() }}
            {{ 'claimOfTotal' | translate }} {{ totalClaims() }}
          </span>
          <div class="claims-page-controls">
            <button
              pButton
              type="button"
              severity="secondary"
              [outlined]="true"
              size="small"
              class="secondary hc-btn-sm"
              [disabled]="loading() || pageNumber() === 0"
              (click)="selectClaimsPage(pageNumber() - 1)"
            >
              {{ 'previous' | translate }}
            </button>
            <span class="hc-muted claims-page-indicator">
              {{ 'claimPageLabel' | translate }} {{ pageNumber() + 1 }} / {{ pageCount() }}
            </span>
            <button
              pButton
              type="button"
              severity="secondary"
              [outlined]="true"
              size="small"
              class="secondary hc-btn-sm"
              [disabled]="loading() || pageNumber() + 1 >= pageCount()"
              (click)="selectClaimsPage(pageNumber() + 1)"
            >
              {{ 'next' | translate }}
            </button>
          </div>
        </div>
      </p-card>

      @if (selectedClaim(); as detail) {
        <section class="card claim-detail hc-prime-dossier" id="claim-detail">
          <div class="claim-detail-hero">
            <div class="claim-detail-heading">
              <div class="claim-detail-identity">
                <p-avatar label="MD" shape="circle" size="xlarge" styleClass="hc-dossier-avatar" />
                <div>
                  <p class="claims-eyebrow">
                    {{ 'claimClinicalDossierEyebrow' | translate }} ·
                    {{ 'selectedClaim' | translate }}
                  </p>
                  <div class="claim-detail-title-row">
                    <h2>{{ detail.externalReference }}</h2>
                    <p-tag
                      [severity]="claimStateSeverity(detail.state)"
                      [rounded]="true"
                      [value]="'claimStates.' + detail.state | translate"
                      [class]="'hc-badge ' + claimStateBadgeClass(detail.state)"
                    />
                    @if (activeClaimRevision(detail); as activeRev) {
                      <p-tag
                        severity="info"
                        [rounded]="true"
                        [value]="'R' + activeRev.revisionNumber"
                      />
                    } @else {
                      <p-tag
                        severity="secondary"
                        [rounded]="true"
                        [value]="'claimDraftRevision' | translate"
                      />
                    }
                  </div>
                  <p class="claim-detail-subtitle">
                    {{ 'claimPatientRef' | translate }}:
                    <span class="hc-mono hc-dossier-patient-pill">{{
                      detail.pseudonymousPatientRef
                    }}</span>
                    @if (detail.stateReason) {
                      <span class="claim-state-reason">· {{ detail.stateReason }}</span>
                    }
                  </p>
                </div>
              </div>
              <button
                pButton
                type="button"
                severity="secondary"
                [outlined]="true"
                size="small"
                class="secondary hc-btn-sm claim-close-btn"
                (click)="closeClaimDetails()"
              >
                {{ 'closeClaimDetails' | translate }}
              </button>
            </div>

            <div
              class="claim-lifecycle-stepper"
              role="group"
              [attr.aria-label]="'claimLifecycleTrackerLabel' | translate"
            >
              @for (step of lifecycleSteps; track step; let idx = $index) {
                <div
                  class="claim-lifecycle-step"
                  [class.is-complete]="isStepComplete(detail.state, step)"
                  [class.is-current]="detail.state === step"
                >
                  <span class="claim-step-marker">
                    @if (isStepComplete(detail.state, step)) {
                      ✓
                    } @else {
                      {{ idx + 1 }}
                    }
                  </span>
                  <div class="claim-step-copy">
                    <strong>{{ 'claimStates.' + step | translate }}</strong>
                  </div>
                </div>
              }
              @if (detail.state === 'VOIDED' || detail.state === 'ARCHIVED') {
                <div class="claim-lifecycle-step is-current is-terminal">
                  <span class="claim-step-marker">!</span>
                  <div class="claim-step-copy">
                    <strong>{{ 'claimStates.' + detail.state | translate }}</strong>
                  </div>
                </div>
              }
            </div>
          </div>

          <dl class="claim-detail-facts">
            <div class="claim-fact-card">
              <dt>{{ 'claimPayer' | translate }}</dt>
              <dd>
                <strong>{{ payerName(detail.payerId) ?? ('unknownValue' | translate) }}</strong>
                @if (payerCode(detail.payerId); as code) {
                  <p-tag severity="secondary" [value]="code" />
                }
              </dd>
            </div>
            <div class="claim-fact-card">
              <dt>{{ 'claimPatientRef' | translate }}</dt>
              <dd>
                <strong class="hc-mono">{{ detail.pseudonymousPatientRef }}</strong>
                <span class="claim-fact-sub">{{ detail.currency }}</span>
              </dd>
            </div>
            <div class="claim-fact-card">
              <dt>{{ 'claimSourceDate' | translate }}</dt>
              <dd>
                <strong>{{
                  activeClaimRevision(detail)?.sourceDate ?? detail.draftSourceDate ?? '—'
                }}</strong>
                <span class="claim-fact-sub">
                  {{ 'claimActiveRevisionLabel' | translate }}:
                  @if (activeClaimRevision(detail); as activeRev) {
                    R{{ activeRev.revisionNumber }}
                  } @else {
                    {{ 'claimDraftRevision' | translate }}
                  }
                </span>
              </dd>
            </div>
            <div class="claim-fact-card claim-fact-card-amount">
              <dt>{{ 'claimTotalBilledLabel' | translate }}</dt>
              <dd>
                @if (activeClaimAmount(detail); as totalAmount) {
                  <strong class="claim-fact-amount">
                    {{ totalAmount.amount }} {{ totalAmount.currency }}
                  </strong>
                } @else {
                  <strong>—</strong>
                }
                <span class="claim-fact-sub">
                  {{ 'claimLastUpdated' | translate }}: {{ detail.updatedAt.slice(0, 10) }}
                </span>
              </dd>
            </div>
          </dl>

          @if (detail.state === 'DRAFT') {
            <p-card styleClass="hc-prime-card claim-draft-banner-card">
              <div class="claim-draft-banner">
                <div class="claim-draft-banner-copy">
                  <p-message severity="warn" styleClass="hc-prime-inline-msg">
                    {{ 'claimDraftReadyBanner' | translate }}
                  </p-message>
                  @if (detail.draftLines.length > 0) {
                    <div class="claim-draft-lines-preview">
                      <h4>{{ 'claimServiceLinesTitle' | translate }}</h4>
                      <p-table
                        [value]="detail.draftLines"
                        styleClass="p-datatable-sm hc-prime-lines-table"
                      >
                        <ng-template #header>
                          <tr>
                            <th scope="col">#</th>
                            <th scope="col">{{ 'claimServiceCode' | translate }}</th>
                            <th scope="col">{{ 'claimLineDescription' | translate }}</th>
                            <th scope="col">{{ 'claimLineAmount' | translate }}</th>
                          </tr>
                        </ng-template>
                        <ng-template #body let-line>
                          <tr>
                            <td class="hc-mono">#{{ line.lineNumber }}</td>
                            <td>
                              <p-tag severity="info" [value]="line.serviceCode" />
                            </td>
                            <td>{{ line.description }}</td>
                            <td class="claim-amount">
                              {{ line.submittedAmount.amount }} {{ line.submittedAmount.currency }}
                            </td>
                          </tr>
                        </ng-template>
                      </p-table>
                    </div>
                  }
                </div>
                @if (api.claimWrite()) {
                  <div class="claim-draft-actions actions">
                    @if (cleanClaimDocuments().length > 0) {
                      <label>
                        {{ 'evidenceDocumentLabel' | translate }}
                        <select
                          class="p-inputtext hc-prime-select"
                          [formControl]="registrationDocumentId"
                        >
                          <option value="">{{ 'noDocumentSelected' | translate }}</option>
                          @for (doc of cleanClaimDocuments(); track doc.id) {
                            <option [value]="doc.id">{{ doc.sourceName }}</option>
                          }
                        </select>
                      </label>
                    }
                    <button
                      pButton
                      type="button"
                      [disabled]="busy()"
                      (click)="registerClaim(detail)"
                    >
                      {{ 'registerClaim' | translate }}
                    </button>
                  </div>
                }
              </div>
            </p-card>
          }

          <div class="claim-detail-grid">
            <div class="claim-detail-col">
              <p-card styleClass="hc-prime-card claim-panel-card">
                <div class="claim-section-header">
                  <div>
                    <h3>{{ 'revisionsTitle' | translate }}</h3>
                    <p class="hc-muted">{{ 'claimRevisionsSubtitle' | translate }}</p>
                  </div>
                  <p-badge [value]="detail.revisions.length" severity="info" />
                </div>

                <div class="claim-revisions-stack">
                  @for (rev of detail.revisions; track rev.id) {
                    <article class="member claim-revision-card">
                      <div class="claim-revision-top">
                        <h4>
                          R{{ rev.revisionNumber }} — {{ rev.submittedAmount.amount }}
                          {{ rev.submittedAmount.currency }} ({{
                            'revisionSubmissionStates.' + rev.submissionState | translate
                          }})
                        </h4>
                        <p-tag
                          [severity]="rev.submissionState === 'SUBMITTED' ? 'success' : 'info'"
                          [rounded]="true"
                          [value]="'revisionSubmissionStates.' + rev.submissionState | translate"
                        />
                      </div>

                      <div class="claim-revision-meta">
                        <span>
                          <strong>{{ 'claimSourceDate' | translate }}:</strong>
                          {{ rev.sourceDate }}
                        </span>
                        <span class="claim-hash-pill">
                          <strong>{{ 'claimHashVerifiedLabel' | translate }}:</strong>
                          <code>{{ rev.contentHash }}</code>
                        </span>
                      </div>

                      @if (rev.changeReason) {
                        <p class="claim-revision-reason">
                          <strong>{{ 'changeReason' | translate }}:</strong> {{ rev.changeReason }}
                        </p>
                      }
                      @if (rev.submissionState === 'SUBMITTED') {
                        <div class="claim-revision-receipt">
                          <p-tag severity="success" [value]="rev.effectiveChannelCode ?? ''" />
                          <span class="hc-mono">{{ rev.effectiveExternalSubmissionRef }}</span>
                          <span>
                            {{ rev.effectiveOccurredOn }} (×{{ rev.externalSubmissionCount }})
                          </span>
                        </div>
                      }

                      <div class="claim-revision-lines">
                        <p-table
                          [value]="rev.lines"
                          styleClass="p-datatable-sm hc-prime-lines-table"
                        >
                          <ng-template #header>
                            <tr>
                              <th scope="col">{{ 'claimLineNumberLabel' | translate }}</th>
                              <th scope="col">{{ 'claimServiceCode' | translate }}</th>
                              <th scope="col">{{ 'claimLineDescription' | translate }}</th>
                              <th scope="col">{{ 'claimLineAmount' | translate }}</th>
                            </tr>
                          </ng-template>
                          <ng-template #body let-line>
                            <tr>
                              <td class="hc-mono">#{{ line.lineNumber }}</td>
                              <td>
                                <p-tag severity="secondary" [value]="line.serviceCode" />
                              </td>
                              <td>{{ line.description }}</td>
                              <td class="claim-amount">
                                {{ line.submittedAmount.amount }}
                                {{ line.submittedAmount.currency }}
                              </td>
                            </tr>
                          </ng-template>
                        </p-table>
                      </div>
                    </article>
                  } @empty {
                    <p class="claims-empty-inline">{{ 'empty' | translate }}</p>
                  }
                </div>

                @if (
                  api.claimManage() &&
                  (detail.state === 'REGISTERED' || detail.state === 'SUBMITTED')
                ) {
                  <div class="claim-subsection-divider">
                    <h3>{{ 'amendClaimTitle' | translate }}</h3>
                    <form
                      class="claim-compact-form"
                      [formGroup]="amendForm"
                      (ngSubmit)="amendRevision(detail)"
                    >
                      <label>
                        {{ 'claimSourceDate' | translate }}
                        <input pInputText type="date" formControlName="sourceDate" />
                      </label>
                      <label>
                        {{ 'changeReason' | translate }}
                        <input pInputText formControlName="changeReason" maxlength="240" />
                      </label>
                      <label>
                        {{ 'claimServiceCode' | translate }}
                        <input pInputText formControlName="serviceCode" maxlength="80" />
                      </label>
                      <label>
                        {{ 'claimLineDescription' | translate }}
                        <input pInputText formControlName="description" maxlength="240" />
                      </label>
                      <label>
                        {{ 'claimLineAmount' | translate }}
                        <input pInputText formControlName="amount" maxlength="40" />
                      </label>
                      @if (cleanClaimDocuments().length > 0) {
                        <label>
                          {{ 'evidenceDocumentLabel' | translate }}
                          <select
                            class="p-inputtext hc-prime-select"
                            formControlName="evidenceDocumentId"
                          >
                            <option value="">{{ 'noDocumentSelected' | translate }}</option>
                            @for (doc of cleanClaimDocuments(); track doc.id) {
                              <option [value]="doc.id">{{ doc.sourceName }}</option>
                            }
                          </select>
                        </label>
                      }
                      <div class="claims-form-actions">
                        <button pButton type="submit" [disabled]="busy()">
                          {{ 'createAmendedRevision' | translate }}
                        </button>
                      </div>
                    </form>
                  </div>
                }
              </p-card>
            </div>

            <div class="claim-detail-col">
              <p-card styleClass="hc-prime-card claim-panel-card">
                <div class="claim-section-header">
                  <div>
                    <h3>{{ 'claimDocumentsTitle' | translate }}</h3>
                    <p class="hc-muted">{{ 'claimEvidenceVaultSubtitle' | translate }}</p>
                  </div>
                  <p-badge [value]="claimDocuments().length" severity="secondary" />
                </div>

                @if (api.claimWrite()) {
                  <div class="claim-upload-box actions">
                    <label class="claim-file-label">
                      {{ 'uploadDocumentLabel' | translate }}
                      <input
                        type="file"
                        accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                        (change)="onClaimFileSelected($event)"
                      />
                    </label>
                    <button
                      pButton
                      type="button"
                      size="small"
                      [disabled]="busy() || !selectedFile()"
                      (click)="uploadClaimDocument(detail)"
                    >
                      {{ 'uploadDocumentButton' | translate }}
                    </button>
                  </div>
                }

                <div class="claim-documents-stack">
                  @for (doc of claimDocuments(); track doc.id) {
                    <article class="member claim-doc-card">
                      <div class="claim-doc-header">
                        <h4>{{ doc.sourceName }} ({{ doc.mimeType }} · {{ doc.byteCount }} B)</h4>
                        <div class="claim-doc-badges">
                          <p-tag
                            [severity]="quarantineSeverity(doc.quarantineStatus)"
                            [rounded]="true"
                            [value]="'quarantineStatuses.' + doc.quarantineStatus | translate"
                          />
                          <p-tag
                            severity="secondary"
                            [value]="'scanStatuses.' + doc.scanStatus | translate"
                          />
                        </div>
                      </div>
                      <p class="claim-hash-pill">
                        SHA-256: <code>{{ doc.sha256Hash }}</code>
                      </p>
                      <div class="actions">
                        @if (doc.quarantineStatus === 'CLEAN') {
                          <a
                            class="secondary hc-doc-link"
                            [href]="'/api/v1/documents/' + doc.id + '/content'"
                          >
                            {{ 'downloadDocument' | translate }}
                          </a>
                          <button
                            pButton
                            type="button"
                            severity="secondary"
                            [outlined]="true"
                            size="small"
                            class="secondary"
                            [disabled]="busy()"
                            (click)="loadEvidence(doc)"
                          >
                            {{ 'inspectEvidenceButton' | translate }}
                          </button>
                          @if (api.claimWrite()) {
                            <button
                              pButton
                              type="button"
                              severity="info"
                              [outlined]="true"
                              size="small"
                              class="secondary"
                              [disabled]="busy()"
                              (click)="extractEvidence(doc)"
                            >
                              {{ 'extractEvidenceButton' | translate }}
                            </button>
                          }
                        } @else if (doc.quarantineStatus === 'QUARANTINED' && api.claimWrite()) {
                          <button
                            pButton
                            type="button"
                            severity="warn"
                            [outlined]="true"
                            size="small"
                            class="secondary"
                            [disabled]="busy()"
                            (click)="retryScan(doc)"
                          >
                            {{ 'retryScanButton' | translate }}
                          </button>
                        }
                      </div>
                    </article>
                  } @empty {
                    <p class="claims-empty-inline">{{ 'empty' | translate }}</p>
                  }
                </div>

                @if (selectedDocument(); as activeDoc) {
                  <div class="claim-subsection-divider claim-evidence-inspector">
                    <h4>{{ 'evidenceVersionsTitle' | translate }}: {{ activeDoc.sourceName }}</h4>
                    @for (ev of evidenceVersions(); track ev.id) {
                      <article class="member claim-evidence-card">
                        <div class="claim-doc-header">
                          <h5>
                            v{{ ev.versionNumber }} · p.{{ ev.pageNumber }} ({{ ev.regionLabel }}) ·
                            {{ 'qualityFlags.' + ev.qualityFlag | translate }}
                          </h5>
                          <p-tag
                            [severity]="qualityFlagSeverity(ev.qualityFlag)"
                            [rounded]="true"
                            [value]="'qualityFlags.' + ev.qualityFlag | translate"
                          />
                        </div>
                        <p class="claim-extracted-text">{{ ev.extractedText }}</p>
                        @if (ev.correctionReason) {
                          <p class="claim-revision-reason">
                            {{ 'evidenceCorrectionReason' | translate }}: {{ ev.correctionReason }}
                          </p>
                        }
                        @if (api.claimWrite()) {
                          <button
                            pButton
                            type="button"
                            severity="secondary"
                            [outlined]="true"
                            size="small"
                            class="secondary"
                            [disabled]="busy()"
                            (click)="selectEvidenceForCorrection(ev)"
                          >
                            {{ 'submitEvidenceCorrection' | translate }}
                          </button>
                        }
                      </article>
                    } @empty {
                      <p class="claims-empty-inline">{{ 'empty' | translate }}</p>
                    }

                    @if (selectedEvidence(); as targetEv) {
                      <form
                        class="claim-compact-form"
                        [formGroup]="correctionForm"
                        (ngSubmit)="submitCorrection(targetEv)"
                      >
                        <label>
                          {{ 'evidenceCorrectionRegion' | translate }}
                          <input pInputText formControlName="regionLabel" maxlength="120" />
                        </label>
                        <label>
                          {{ 'evidenceCorrectionText' | translate }}
                          <input pInputText formControlName="extractedText" maxlength="8000" />
                        </label>
                        <label>
                          {{ 'evidenceCorrectionReason' | translate }}
                          <input pInputText formControlName="correctionReason" maxlength="240" />
                        </label>
                        <div class="claims-form-actions">
                          <button pButton type="submit" [disabled]="busy()">
                            {{ 'submitEvidenceCorrection' | translate }}
                          </button>
                        </div>
                      </form>
                    }
                  </div>
                }
              </p-card>

              @if (detail.state === 'REGISTERED' || detail.state === 'SUBMITTED') {
                <p-card styleClass="hc-prime-card claim-panel-card">
                  <div class="claim-section-header">
                    <div>
                      <h3>{{ 'submissionHistoryTitle' | translate }}</h3>
                      <p class="hc-muted">{{ 'claimSubmissionTimelineSubtitle' | translate }}</p>
                    </div>
                    <p-badge [value]="submissionEvents().length" severity="success" />
                  </div>

                  @if (api.claimWrite()) {
                    <form
                      class="claim-compact-form"
                      [formGroup]="submissionForm"
                      (ngSubmit)="recordSubmission(detail)"
                    >
                      <label>
                        {{ 'submissionRevision' | translate }}
                        <select class="p-inputtext hc-prime-select" formControlName="revisionId">
                          @for (rev of detail.revisions; track rev.id) {
                            <option [value]="rev.id">R{{ rev.revisionNumber }}</option>
                          }
                        </select>
                      </label>
                      <label>
                        {{ 'submissionEventKind' | translate }}
                        <select class="p-inputtext hc-prime-select" formControlName="eventKind">
                          @for (kind of availableEventKinds(); track kind) {
                            <option [value]="kind">
                              {{ 'submissionEventKinds.' + kind | translate }}
                            </option>
                          }
                        </select>
                      </label>
                      <label>
                        {{ 'submissionChannel' | translate }}
                        <input pInputText formControlName="channelCode" maxlength="60" />
                      </label>
                      <label>
                        {{ 'submissionExternalRef' | translate }}
                        <input pInputText formControlName="externalSubmissionRef" maxlength="120" />
                      </label>
                      <label>
                        {{ 'submissionOccurredOn' | translate }}
                        <input pInputText type="date" formControlName="occurredOn" />
                      </label>
                      @if (cleanClaimDocuments().length > 0) {
                        <label>
                          {{ 'evidenceDocumentLabel' | translate }}
                          <select
                            class="p-inputtext hc-prime-select"
                            formControlName="evidenceDocumentId"
                          >
                            <option value="">{{ 'noDocumentSelected' | translate }}</option>
                            @for (doc of cleanClaimDocuments(); track doc.id) {
                              <option [value]="doc.id">{{ doc.sourceName }}</option>
                            }
                          </select>
                        </label>
                      }
                      @if (submissionForm.controls.eventKind.value !== 'SUBMISSION') {
                        <label>
                          {{ 'submissionTargetEvent' | translate }}
                          <select
                            class="p-inputtext hc-prime-select"
                            formControlName="targetEventId"
                          >
                            <option value="">—</option>
                            @for (ev of submissionEvents(); track ev.id) {
                              @if (!ev.superseded) {
                                <option [value]="ev.id">
                                  R{{ ev.revisionNumber }} · {{ ev.channelCode }} ·
                                  {{ ev.externalSubmissionRef }}
                                </option>
                              }
                            }
                          </select>
                        </label>
                        <label>
                          {{ 'correctionReason' | translate }}
                          <input pInputText formControlName="correctionReason" maxlength="240" />
                        </label>
                      }
                      <div class="claims-form-actions">
                        <button pButton type="submit" [disabled]="busy()">
                          {{ 'recordSubmission' | translate }}
                        </button>
                      </div>
                    </form>
                  }

                  @if (submissionEvents().length > 0) {
                    <div class="claim-timeline-wrap">
                      <p-timeline [value]="submissionEvents()" styleClass="hc-prime-timeline">
                        <ng-template #marker let-ev>
                          <span
                            class="hc-timeline-marker"
                            [class.is-superseded]="ev.superseded"
                          ></span>
                        </ng-template>
                        <ng-template #content let-ev>
                          <article class="member claim-timeline-event">
                            <div class="claim-doc-header">
                              <h4>
                                R{{ ev.revisionNumber }} ·
                                {{ 'submissionEventKinds.' + ev.eventKind | translate }} ·
                                {{ ev.channelCode }} · {{ ev.externalSubmissionRef }}
                              </h4>
                              <p-tag
                                [severity]="ev.superseded ? 'secondary' : 'success'"
                                [rounded]="true"
                                [value]="'submissionEventKinds.' + ev.eventKind | translate"
                              />
                            </div>
                            <p class="hc-muted hc-m-0">
                              {{ ev.occurredOn }} · {{ 'supersededLabel' | translate }}:
                              {{ (ev.superseded ? 'yes' : 'no') | translate }}
                            </p>
                            @if (ev.correctionReason) {
                              <p class="claim-revision-reason">
                                {{ 'correctionReason' | translate }}: {{ ev.correctionReason }}
                              </p>
                            }
                          </article>
                        </ng-template>
                      </p-timeline>
                    </div>
                  } @else {
                    <p class="claims-empty-inline">{{ 'empty' | translate }}</p>
                  }
                </p-card>
              }

              @if (api.claimManage()) {
                <p-card styleClass="hc-prime-card claim-panel-card claim-governance-card">
                  <div class="claim-section-header">
                    <div>
                      <h3>{{ 'claimLifecycleGovernanceTitle' | translate }}</h3>
                      <p class="hc-muted">{{ 'claimLifecycleGovernanceSubtitle' | translate }}</p>
                    </div>
                  </div>
                  <form
                    class="claim-compact-form"
                    [formGroup]="transitionForm"
                    (ngSubmit)="submitDefaultTransition(detail)"
                  >
                    <label>
                      {{ 'transitionReason' | translate }}
                      <input pInputText formControlName="reason" maxlength="240" />
                    </label>
                    <div class="actions">
                      @if (detail.state === 'REGISTERED' && submissionEvents().length === 0) {
                        <button
                          pButton
                          type="button"
                          severity="danger"
                          [outlined]="true"
                          class="secondary"
                          [disabled]="busy()"
                          (click)="transitionState(detail, 'VOID')"
                        >
                          {{ 'voidClaim' | translate }}
                        </button>
                      }
                      @if (detail.state === 'SUBMITTED') {
                        <button
                          pButton
                          type="button"
                          severity="secondary"
                          [outlined]="true"
                          class="secondary"
                          [disabled]="busy()"
                          (click)="transitionState(detail, 'ARCHIVE')"
                        >
                          {{ 'archiveClaim' | translate }}
                        </button>
                      }
                      @if (detail.state === 'ARCHIVED') {
                        <button
                          pButton
                          type="button"
                          severity="info"
                          [outlined]="true"
                          class="secondary"
                          [disabled]="busy()"
                          (click)="transitionState(detail, 'REOPEN')"
                        >
                          {{ 'reopenClaim' | translate }}
                        </button>
                      }
                    </div>
                  </form>
                </p-card>
              }
            </div>
          </div>
        </section>
      }
    }
  </div>`,
})
export class ClaimsPage {
  readonly api = inject(Api);
  private readonly fb = inject(FormBuilder);
  private readonly destroy = inject(DestroyRef);

  readonly payers = signal<Payer[]>([]);
  readonly claims = signal<ClaimSummary[]>([]);
  readonly totalClaims = signal(0);
  readonly pageNumber = signal(0);
  readonly pageSize = signal(25);
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.totalClaims() / this.pageSize())));
  readonly pageFirst = computed(() =>
    this.totalClaims() === 0 ? 0 : this.pageNumber() * this.pageSize() + 1,
  );
  readonly pageLast = computed(() =>
    Math.min((this.pageNumber() + 1) * this.pageSize(), this.totalClaims()),
  );
  readonly claimStates: readonly ClaimState[] = [
    'DRAFT',
    'REGISTERED',
    'SUBMITTED',
    'VOIDED',
    'ARCHIVED',
  ];
  readonly lifecycleSteps: readonly ClaimState[] = ['DRAFT', 'REGISTERED', 'SUBMITTED'];
  readonly statusFilter = signal<'ALL' | ClaimState>('ALL');
  readonly claimSearch = signal('');
  readonly showCreateForm = signal(false);
  readonly statusCounts = computed(() => {
    const counts: Record<ClaimState, number> = {
      DRAFT: 0,
      REGISTERED: 0,
      SUBMITTED: 0,
      VOIDED: 0,
      ARCHIVED: 0,
    };
    for (const claim of this.claims()) counts[claim.state] += 1;
    return counts;
  });
  readonly filteredClaims = computed(() => {
    const query = this.claimSearch().trim().toLocaleLowerCase();
    const status = this.statusFilter();
    return this.claims().filter((claim) => {
      if (status !== 'ALL' && claim.state !== status) return false;
      if (!query) return true;
      return [
        claim.externalReference,
        claim.pseudonymousPatientRef,
        this.payerName(claim.payerId) ?? '',
        this.payerCode(claim.payerId) ?? '',
      ].some((value) => value.toLocaleLowerCase().includes(query));
    });
  });
  readonly selectedClaim = signal<ClaimDetail | null>(null);
  readonly submissionEvents = signal<ClaimSubmissionEvent[]>([]);
  readonly claimDocuments = signal<DocumentMetadata[]>([]);
  readonly cleanClaimDocuments = computed(() =>
    this.claimDocuments().filter((d) => d.quarantineStatus === 'CLEAN'),
  );
  readonly selectedFile = signal<File | null>(null);
  readonly selectedDocument = signal<DocumentMetadata | null>(null);
  readonly evidenceVersions = signal<EvidenceVersion[]>([]);
  readonly selectedEvidence = signal<EvidenceVersion | null>(null);
  readonly registrationDocumentId = new FormControl('', { nonNullable: true });
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  private idempotencySeq = 0;

  readonly correctionForm = this.fb.nonNullable.group({
    regionLabel: ['', [Validators.required, Validators.maxLength(120)]],
    extractedText: ['', [Validators.required, Validators.maxLength(8000)]],
    correctionReason: ['', [Validators.required, Validators.maxLength(240)]],
  });

  readonly draftForm = this.fb.nonNullable.group({
    payerId: ['', Validators.required],
    externalReference: ['', [Validators.required, Validators.maxLength(120)]],
    pseudonymousPatientRef: ['', [Validators.required, Validators.maxLength(120)]],
    sourceDate: ['', Validators.required],
    serviceCode: ['', [Validators.required, Validators.maxLength(80)]],
    description: ['', [Validators.required, Validators.maxLength(240)]],
    amount: ['', [Validators.required, Validators.maxLength(40)]],
  });

  readonly amendForm = this.fb.nonNullable.group({
    sourceDate: ['', Validators.required],
    changeReason: ['', [Validators.required, Validators.maxLength(240)]],
    serviceCode: ['', [Validators.required, Validators.maxLength(80)]],
    description: ['', [Validators.required, Validators.maxLength(240)]],
    amount: ['', [Validators.required, Validators.maxLength(40)]],
    evidenceDocumentId: [''],
  });

  readonly submissionForm = this.fb.nonNullable.group({
    revisionId: ['', Validators.required],
    eventKind: ['SUBMISSION' as SubmissionEventKind, Validators.required],
    channelCode: ['BORDEREAU_PAPIER', [Validators.required, Validators.maxLength(60)]],
    externalSubmissionRef: ['', [Validators.required, Validators.maxLength(120)]],
    occurredOn: ['', Validators.required],
    occurrencePrecision: ['DATE' as OccurrencePrecision, Validators.required],
    evidenceDocumentId: [''],
    targetEventId: [''],
    correctionReason: ['', Validators.maxLength(240)],
  });

  readonly transitionForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(240)]],
  });

  constructor() {
    if (this.api.claimRead()) {
      this.loadAll();
    }
  }

  availableEventKinds(): readonly SubmissionEventKind[] {
    return this.api.claimManage()
      ? submissionEventKinds
      : (['SUBMISSION', 'RESUBMISSION'] as const);
  }

  onClaimSearch(event: Event) {
    this.claimSearch.set((event.target as HTMLInputElement).value);
  }

  payerName(payerId: string): string | undefined {
    return this.payers().find((payer) => payer.id === payerId)?.originalName;
  }

  payerCode(payerId: string): string | undefined {
    return this.payers().find((payer) => payer.id === payerId)?.code;
  }

  activeClaimRevision(detail: ClaimDetail): ClaimRevision | null {
    if (detail.activeRevisionId) {
      const matched = detail.revisions.find((rev) => rev.id === detail.activeRevisionId);
      if (matched) return matched;
    }
    return detail.revisions.at(-1) ?? null;
  }

  activeClaimAmount(detail: ClaimDetail): Money | null {
    const activeRev = this.activeClaimRevision(detail);
    if (activeRev) return activeRev.submittedAmount;
    return detail.draftLines[0]?.submittedAmount ?? null;
  }

  isStepComplete(currentState: ClaimState, step: ClaimState): boolean {
    const order: Record<ClaimState, number> = {
      DRAFT: 0,
      REGISTERED: 1,
      SUBMITTED: 2,
      ARCHIVED: 3,
      VOIDED: -1,
    };
    return order[currentState] > order[step];
  }

  claimStateBadgeClass(state: ClaimState): string {
    switch (state) {
      case 'DRAFT':
      case 'ARCHIVED':
        return 'hc-badge-neutral';
      case 'REGISTERED':
        return 'hc-badge-brand';
      case 'SUBMITTED':
        return 'hc-badge-success';
      case 'VOIDED':
        return 'hc-badge-danger';
    }
  }

  claimStateSeverity(state: ClaimState): PrimeTagSeverity {
    switch (state) {
      case 'DRAFT':
      case 'ARCHIVED':
        return 'secondary';
      case 'REGISTERED':
        return 'info';
      case 'SUBMITTED':
        return 'success';
      case 'VOIDED':
        return 'danger';
    }
  }

  quarantineSeverity(status: QuarantineStatus): PrimeTagSeverity {
    switch (status) {
      case 'CLEAN':
        return 'success';
      case 'QUARANTINED':
        return 'warn';
      case 'REJECTED':
        return 'danger';
    }
  }

  qualityFlagSeverity(flag: QualityFlag): PrimeTagSeverity {
    switch (flag) {
      case 'HUMAN_VERIFIED':
        return 'success';
      case 'EXTRACTED':
        return 'info';
      case 'NEEDS_OCR_OR_REVIEW':
        return 'warn';
    }
  }

  closeClaimDetails() {
    this.selectedClaim.set(null);
    this.submissionEvents.set([]);
    this.claimDocuments.set([]);
    this.selectedDocument.set(null);
    this.evidenceVersions.set([]);
    this.selectedEvidence.set(null);
    this.selectedFile.set(null);
  }

  loadAll() {
    this.loading.set(true);
    this.error.set('');
    forkJoin({
      payers: this.api.get<Page<Payer>>('/payers?page=0'),
      claims: this.api.get<Page<ClaimSummary>>('/claims?page=0'),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: ({ payers, claims }) => {
          this.payers.set(payers.items);
          this.claims.set(claims.items);
          this.totalClaims.set(claims.totalElements);
          this.pageNumber.set(claims.page);
          this.pageSize.set(claims.size);
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  selectClaimsPage(page: number) {
    if (page < 0 || page >= this.pageCount() || page === this.pageNumber() || this.loading()) {
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.api
      .get<Page<ClaimSummary>>(`/claims?page=${page}`)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (result) => {
          this.claims.set(result.items);
          this.totalClaims.set(result.totalElements);
          this.pageNumber.set(result.page);
          this.pageSize.set(result.size);
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  createDraft() {
    if (this.draftForm.invalid) {
      this.error.set('validation');
      return;
    }
    const raw = this.draftForm.getRawValue();
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<ClaimDetail>('POST', '/claims', {
        payerId: raw.payerId,
        externalReference: raw.externalReference,
        pseudonymousPatientRef: raw.pseudonymousPatientRef,
        sourceDate: raw.sourceDate,
        lines: [
          {
            serviceCode: raw.serviceCode,
            description: raw.description,
            amount: raw.amount,
          },
        ],
      })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (detail) => {
          this.selectedClaim.set(detail);
          this.showCreateForm.set(false);
          this.submissionEvents.set([]);
          this.claimDocuments.set([]);
          this.selectedDocument.set(null);
          this.evidenceVersions.set([]);
          this.selectedEvidence.set(null);
          this.draftForm.reset({
            payerId: raw.payerId,
            externalReference: '',
            pseudonymousPatientRef: '',
            sourceDate: raw.sourceDate,
            serviceCode: '',
            description: '',
            amount: '',
          });
          this.message.set('saved');
          this.refreshClaimsList(0);
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  selectClaim(claimId: string) {
    this.busy.set(true);
    this.error.set('');
    this.selectedDocument.set(null);
    this.evidenceVersions.set([]);
    this.selectedEvidence.set(null);
    forkJoin({
      detail: this.api.get<ClaimDetail>(`/claims/${claimId}`),
      events: this.api.get<ClaimSubmissionEvent[]>(`/claims/${claimId}/submission-records`),
      docs: this.api.get<Page<DocumentMetadata>>(`/claims/${claimId}/documents?page=0`),
    })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: ({ detail, events, docs }) => {
          this.claimDocuments.set(docs.items);
          this.applyDetailAndEvents(detail, events);
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  onClaimFileSelected(event: Event) {
    const input = event.target as HTMLInputElement | null;
    this.selectedFile.set(input?.files?.[0] ?? null);
  }

  uploadClaimDocument(detail: ClaimDetail) {
    const file = this.selectedFile();
    if (!file) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .uploadDocument(file, { claimId: detail.id })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.claimDocuments.update((list) => [created, ...list]);
          this.selectedFile.set(null);
          this.message.set('saved');
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  retryScan(doc: DocumentMetadata) {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<DocumentMetadata>('POST', `/documents/${doc.id}/scan-retries`, {}, doc.version)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.claimDocuments.update((list) =>
            list.map((d) => (d.id === updated.id ? updated : d)),
          );
          this.message.set('saved');
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  loadEvidence(doc: DocumentMetadata) {
    this.selectedDocument.set(doc);
    this.selectedEvidence.set(null);
    this.busy.set(true);
    this.error.set('');
    this.api
      .get<EvidenceVersion[]>(`/documents/${doc.id}/evidence`)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (items) => this.evidenceVersions.set(items),
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  extractEvidence(doc: DocumentMetadata) {
    this.selectedDocument.set(doc);
    this.selectedEvidence.set(null);
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<EvidenceVersion[]>('POST', `/documents/${doc.id}/extractions`)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (items) => {
          this.evidenceVersions.set(items);
          this.message.set('saved');
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  selectEvidenceForCorrection(ev: EvidenceVersion) {
    this.selectedEvidence.set(ev);
    this.correctionForm.reset({
      regionLabel: ev.regionLabel,
      extractedText: ev.extractedText,
      correctionReason: '',
    });
  }

  submitCorrection(targetEv: EvidenceVersion) {
    if (this.correctionForm.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<EvidenceVersion>(
        'POST',
        `/evidence/${targetEv.id}/corrections`,
        this.correctionForm.getRawValue(),
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.evidenceVersions.update((list) => [...list, created]);
          this.selectedEvidence.set(null);
          this.message.set('saved');
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  registerClaim(detail: ClaimDetail) {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    const evidenceDocumentId = this.registrationDocumentId.value || null;
    this.api
      .mutate<ClaimDetail>(
        'POST',
        `/claims/${detail.id}/registration`,
        { evidenceDocumentId },
        detail.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.applyDetailAndEvents(updated, this.submissionEvents());
          this.message.set('saved');
          this.refreshClaimsList();
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  amendRevision(detail: ClaimDetail) {
    if (this.amendForm.invalid) {
      this.error.set('validation');
      return;
    }
    const raw = this.amendForm.getRawValue();
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<ClaimDetail>(
        'POST',
        `/claims/${detail.id}/revisions`,
        {
          sourceDate: raw.sourceDate,
          changeReason: raw.changeReason,
          evidenceDocumentId: raw.evidenceDocumentId ? raw.evidenceDocumentId : null,
          lines: [
            {
              serviceCode: raw.serviceCode,
              description: raw.description,
              amount: raw.amount,
            },
          ],
        },
        detail.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.applyDetailAndEvents(updated, this.submissionEvents());
          this.amendForm.reset({
            sourceDate: raw.sourceDate,
            changeReason: '',
            serviceCode: '',
            description: '',
            amount: '',
            evidenceDocumentId: '',
          });
          this.message.set('saved');
          this.refreshClaimsList();
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  recordSubmission(detail: ClaimDetail) {
    if (this.submissionForm.invalid) {
      this.error.set('validation');
      return;
    }
    const raw = this.submissionForm.getRawValue();
    const idempotencyKey = `ui-${detail.id}-${++this.idempotencySeq}`;
    const body = {
      revisionId: raw.revisionId,
      eventKind: raw.eventKind,
      channelCode: raw.channelCode,
      externalSubmissionRef: raw.externalSubmissionRef,
      occurredOn: raw.occurredOn,
      occurrencePrecision: raw.occurrencePrecision,
      evidenceDocumentId: raw.evidenceDocumentId ? raw.evidenceDocumentId : null,
      resubmissionOfId:
        raw.eventKind === 'RESUBMISSION' && raw.targetEventId ? raw.targetEventId : null,
      supersedesId:
        raw.eventKind === 'METADATA_CORRECTION' && raw.targetEventId ? raw.targetEventId : null,
      correctionReason: raw.correctionReason ? raw.correctionReason : null,
      idempotencyKey,
    };
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<ClaimSubmissionEvent>(
        'POST',
        `/claims/${detail.id}/submission-records`,
        body,
        undefined,
        { 'Idempotency-Key': idempotencyKey },
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => {
          this.message.set('saved');
          this.selectClaim(detail.id);
          this.refreshClaimsList();
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  submitDefaultTransition(detail: ClaimDetail) {
    if (detail.state === 'REGISTERED') this.transitionState(detail, 'VOID');
    else if (detail.state === 'SUBMITTED') this.transitionState(detail, 'ARCHIVE');
    else if (detail.state === 'ARCHIVED') this.transitionState(detail, 'REOPEN');
  }

  transitionState(detail: ClaimDetail, action: ClaimTransitionAction) {
    if (this.transitionForm.invalid) {
      this.error.set('validation');
      return;
    }
    const reason = this.transitionForm.controls.reason.value;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<ClaimDetail>(
        'POST',
        `/claims/${detail.id}/state-transitions`,
        { action, reason },
        detail.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.applyDetailAndEvents(updated, this.submissionEvents());
          this.transitionForm.reset();
          this.message.set('saved');
          this.refreshClaimsList();
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }

  private applyDetailAndEvents(detail: ClaimDetail, events: ClaimSubmissionEvent[]) {
    this.selectedClaim.set(detail);
    this.submissionEvents.set(events);
    if (detail.activeRevisionId && !this.submissionForm.controls.revisionId.dirty) {
      this.submissionForm.controls.revisionId.setValue(detail.activeRevisionId);
    }
  }

  private refreshClaimsList(page = this.pageNumber()) {
    this.api
      .get<Page<ClaimSummary>>(`/claims?page=${page}`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (result) => {
          this.claims.set(result.items);
          this.totalClaims.set(result.totalElements);
          this.pageNumber.set(result.page);
          this.pageSize.set(result.size);
        },
        error: (err) => this.error.set(errorKey(err)),
      });
  }
}
