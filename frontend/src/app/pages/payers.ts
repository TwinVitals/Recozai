import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin } from 'rxjs';
import { Avatar } from 'primeng/avatar';
import { ButtonDirective } from 'primeng/button';
import { Card } from 'primeng/card';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import {
  Api,
  DenialCategory,
  DocumentMetadata,
  EvidenceVersion,
  Page,
  Payer,
  RuleConfig,
  RuleKind,
  RuleVersion,
  WorkflowStepKey,
  errorKey,
  ruleKinds,
  workflowStepKeys,
} from '../core/api.service';

@Component({
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    Avatar,
    ButtonDirective,
    Card,
    InputText,
    Message,
    TableModule,
    Tag,
  ],
  template: `<main class="payers-page hc-prime-payers">
    @if (!api.payerRead()) {
      <p-card styleClass="hc-prime-card">
        <h1>{{ 'payers' | translate }}</h1>
        <p-message severity="error" role="alert">{{ 'errors.FORBIDDEN' | translate }}</p-message>
      </p-card>
    } @else {
      <header class="payers-header hc-page-header">
        <div class="payers-header-identity">
          <p-avatar label="P" shape="circle" size="large" styleClass="payers-brand-avatar" />
          <div>
            <p class="payers-eyebrow">{{ 'payerSetupEyebrow' | translate }}</p>
            <h1>{{ 'payers' | translate }}</h1>
            <p class="payers-subtitle">{{ 'payersWorkspaceSubtitle' | translate }}</p>
          </div>
        </div>
      </header>

      @if (!api.payerManage()) {
        <p-message severity="info" styleClass="hc-prime-notice" role="status">
          {{ 'payerReadOnlyNotice' | translate }}
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

      <div class="payers-configuration-grid">
        <p-card styleClass="hc-prime-card payer-panel">
          <div class="payer-panel-heading">
            <div>
              <h2>{{ 'payerDirectoryTitle' | translate }}</h2>
            </div>
            @if (api.payerManage()) {
              <button
                pButton
                type="button"
                class="hc-btn"
                [attr.aria-expanded]="showPayerForm()"
                (click)="showPayerForm.update((shown) => !shown)"
              >
                {{ (showPayerForm() ? 'closeClaimForm' : 'createPayer') | translate }}
              </button>
            }
          </div>

          @if (showPayerForm() && api.payerManage()) {
            <form
              class="payers-form payer-create-form"
              [formGroup]="payerForm"
              (ngSubmit)="createPayer()"
            >
              <label>
                {{ 'payerOriginalName' | translate }}
                <input pInputText formControlName="originalName" maxlength="160" />
              </label>
              <label>
                {{ 'payerCode' | translate }}
                <input pInputText formControlName="code" maxlength="60" />
              </label>
              <label>
                {{ 'payerType' | translate }}
                <input pInputText formControlName="typeCode" maxlength="60" />
              </label>
              <button pButton type="submit" [disabled]="busy()">
                {{ 'createPayer' | translate }}
              </button>
            </form>
          }

          <label class="payers-search">
            <span class="sr-only">{{ 'searchPayersPlaceholder' | translate }}</span>
            <input
              pInputText
              type="search"
              [placeholder]="'searchPayersPlaceholder' | translate"
              [value]="payerSearch()"
              (input)="updatePayerSearch($event)"
            />
          </label>

          <p-table
            [value]="filteredPayers()"
            [rowHover]="true"
            styleClass="p-datatable-sm hc-prime-datatable"
            tableStyleClass="payers-table"
          >
            <ng-template #header>
              <tr>
                <th scope="col">{{ 'payerOriginalName' | translate }}</th>
                <th scope="col">{{ 'payerType' | translate }}</th>
                <th scope="col">{{ 'active' | translate }}</th>
                <th scope="col">{{ 'action' | translate }}</th>
              </tr>
            </ng-template>
            <ng-template #body let-payer>
              <tr>
                <td>
                  <div class="payer-name-cell">
                    <strong>{{ payer.originalName }}</strong>
                    <span>{{ payer.code }}</span>
                  </div>
                </td>
                <td><p-tag severity="info" [value]="payer.typeCode" /></td>
                <td>
                  <p-tag
                    [severity]="payer.active ? 'success' : 'secondary'"
                    [rounded]="true"
                    [value]="(payer.active ? 'active' : 'inactive') | translate"
                  />
                </td>
                <td>
                  <div class="payer-row-actions">
                    <button
                      pButton
                      type="button"
                      severity="secondary"
                      [outlined]="true"
                      size="small"
                      [disabled]="busy()"
                      (click)="selectPayer(payer)"
                    >
                      {{ 'selectPayerForRules' | translate }}
                    </button>
                    @if (api.payerManage()) {
                      <button
                        pButton
                        type="button"
                        severity="secondary"
                        [text]="true"
                        size="small"
                        [disabled]="busy()"
                        (click)="togglePayer(payer)"
                      >
                        {{ (payer.active ? 'deactivatePayer' : 'activatePayer') | translate }}
                      </button>
                    }
                  </div>
                </td>
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td colspan="4" class="payer-table-empty">{{ 'empty' | translate }}</td>
              </tr>
            </ng-template>
          </p-table>
        </p-card>

        <p-card styleClass="hc-prime-card payer-panel categories-panel">
          <div class="payer-panel-heading">
            <div>
              <h2>{{ 'categoriesTitle' | translate }}</h2>
            </div>
            @if (api.payerManage()) {
              <button
                pButton
                type="button"
                severity="secondary"
                [outlined]="true"
                class="hc-btn"
                [attr.aria-expanded]="showCategoryForm()"
                (click)="showCategoryForm.update((shown) => !shown)"
              >
                {{ (showCategoryForm() ? 'closeClaimForm' : 'createCategory') | translate }}
              </button>
            }
          </div>

          @if (showCategoryForm() && api.payerManage()) {
            <form
              class="payers-form category-create-form"
              [formGroup]="categoryForm"
              (ngSubmit)="createCategory()"
            >
              <label>
                {{ 'categoryKey' | translate }}
                <input pInputText formControlName="key" maxlength="80" />
              </label>
              <label>
                {{ 'categoryLabelFr' | translate }}
                <input pInputText formControlName="labelFr" maxlength="160" />
              </label>
              <label>
                {{ 'categoryLabelEn' | translate }}
                <input pInputText formControlName="labelEn" maxlength="160" />
              </label>
              <button pButton type="submit" [disabled]="busy()">
                {{ 'createCategory' | translate }}
              </button>
            </form>
          }

          <p-table
            [value]="categories()"
            [rowHover]="true"
            styleClass="p-datatable-sm hc-prime-datatable"
            tableStyleClass="categories-table"
          >
            <ng-template #header>
              <tr>
                <th scope="col">{{ 'categoryKey' | translate }}</th>
                <th scope="col">{{ 'categoryLabelFr' | translate }}</th>
                <th scope="col">{{ 'categoryLabelEn' | translate }}</th>
                <th scope="col">{{ 'active' | translate }}</th>
                @if (api.payerManage()) {
                  <th scope="col">{{ 'action' | translate }}</th>
                }
              </tr>
            </ng-template>
            <ng-template #body let-category>
              <tr>
                <td>
                  <code>{{ category.key }}</code>
                </td>
                <td>{{ category.labelFr }}</td>
                <td>{{ category.labelEn }}</td>
                <td>
                  <p-tag
                    [severity]="category.active ? 'success' : 'secondary'"
                    [rounded]="true"
                    [value]="(category.active ? 'active' : 'inactive') | translate"
                  />
                </td>
                @if (api.payerManage()) {
                  <td>
                    <button
                      pButton
                      type="button"
                      severity="secondary"
                      [text]="true"
                      size="small"
                      [disabled]="busy()"
                      (click)="toggleCategory(category)"
                    >
                      {{
                        (category.active ? 'deactivateCategory' : 'activateCategory') | translate
                      }}
                    </button>
                  </td>
                }
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td [attr.colspan]="api.payerManage() ? 5 : 4" class="payer-table-empty">
                  {{ 'empty' | translate }}
                </td>
              </tr>
            </ng-template>
          </p-table>
        </p-card>
      </div>

      @if (selectedPayer(); as activePayer) {
        <p-card styleClass="hc-prime-card payer-workspace">
          <div class="payer-workspace-heading">
            <div>
              <p class="payers-eyebrow">{{ 'selectedPayer' | translate }}</p>
              <h2>{{ activePayer.originalName }}</h2>
              <p class="payer-workspace-meta">
                <span>{{ activePayer.code }}</span>
                <span>{{ activePayer.typeCode }}</span>
              </p>
            </div>
            <div class="payer-workspace-status">
              <p-tag
                [severity]="activePayer.active ? 'success' : 'secondary'"
                [rounded]="true"
                [value]="(activePayer.active ? 'active' : 'inactive') | translate"
              />
              <button
                pButton
                type="button"
                severity="secondary"
                [text]="true"
                size="small"
                (click)="clearSelectedPayer()"
              >
                {{ 'closeClaimForm' | translate }}
              </button>
            </div>
          </div>
          <div class="payer-workspace-content">
            <section class="payer-detail-section">
              <h3>{{ 'payerDocumentsTitle' | translate }}</h3>
              @if (api.payerManage()) {
                <div class="payer-upload-row">
                  <label class="payer-file-label">
                    {{ 'uploadDocumentLabel' | translate }}
                    <input
                      type="file"
                      accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                      (change)="onPayerFileSelected($event)"
                    />
                  </label>
                  <button
                    pButton
                    type="button"
                    [disabled]="busy() || !selectedFile()"
                    (click)="uploadPayerDocument(activePayer)"
                  >
                    {{ 'uploadDocumentButton' | translate }}
                  </button>
                </div>
              }
              @for (doc of payerDocuments(); track doc.id) {
                <article class="payer-document-row">
                  <div class="payer-document-summary">
                    <strong>{{ doc.sourceName }}</strong>
                    <span>{{ doc.mimeType }} · {{ doc.byteCount }} B</span>
                  </div>
                  <div class="payer-document-status">
                    <p-tag
                      [severity]="doc.quarantineStatus === 'CLEAN' ? 'success' : 'warn'"
                      [value]="'quarantineStatuses.' + doc.quarantineStatus | translate"
                    />
                    <p-tag
                      severity="secondary"
                      [value]="'scanStatuses.' + doc.scanStatus | translate"
                    />
                  </div>
                  <p class="payer-document-hash">
                    SHA-256: <code>{{ doc.sha256Hash }}</code>
                  </p>
                  <div class="payer-row-actions">
                    @if (doc.quarantineStatus === 'CLEAN') {
                      <a
                        pButton
                        severity="secondary"
                        [outlined]="true"
                        [href]="'/api/v1/documents/' + doc.id + '/content'"
                      >
                        {{ 'downloadDocument' | translate }}
                      </a>
                      <button
                        pButton
                        type="button"
                        severity="secondary"
                        [outlined]="true"
                        [disabled]="busy()"
                        (click)="loadEvidence(doc)"
                      >
                        {{ 'inspectEvidenceButton' | translate }}
                      </button>
                      @if (api.payerManage()) {
                        <button
                          pButton
                          type="button"
                          severity="secondary"
                          [outlined]="true"
                          [disabled]="busy()"
                          (click)="extractEvidence(doc)"
                        >
                          {{ 'extractEvidenceButton' | translate }}
                        </button>
                      }
                    } @else if (doc.quarantineStatus === 'QUARANTINED' && api.payerManage()) {
                      <button
                        pButton
                        type="button"
                        severity="secondary"
                        [outlined]="true"
                        [disabled]="busy()"
                        (click)="retryScan(doc)"
                      >
                        {{ 'retryScanButton' | translate }}
                      </button>
                    }
                  </div>
                </article>
              } @empty {
                <p>{{ 'empty' | translate }}</p>
              }

              @if (selectedDocument(); as activeDoc) {
                <div class="payer-evidence-heading">
                  <h4>{{ 'evidenceVersionsTitle' | translate }}</h4>
                  <span>{{ activeDoc.sourceName }}</span>
                </div>
                @for (ev of evidenceVersions(); track ev.id) {
                  <article class="payer-evidence-row">
                    <div class="payer-evidence-title">
                      <strong>
                        v{{ ev.versionNumber }} · p.{{ ev.pageNumber }} ({{ ev.regionLabel }}) ·
                        {{ 'qualityFlags.' + ev.qualityFlag | translate }}
                      </strong>
                      <p-tag
                        [severity]="
                          ev.qualityFlag === 'HUMAN_VERIFIED'
                            ? 'success'
                            : ev.qualityFlag === 'NEEDS_OCR_OR_REVIEW'
                              ? 'warn'
                              : 'info'
                        "
                        [value]="'qualityFlags.' + ev.qualityFlag | translate"
                      />
                    </div>
                    <p>{{ ev.extractedText }}</p>
                    @if (ev.correctionReason) {
                      <p>{{ 'evidenceCorrectionReason' | translate }}: {{ ev.correctionReason }}</p>
                    }
                    @if (api.payerManage()) {
                      <button
                        pButton
                        type="button"
                        severity="secondary"
                        [outlined]="true"
                        [disabled]="busy()"
                        (click)="selectEvidenceForCorrection(ev)"
                      >
                        {{ 'submitEvidenceCorrection' | translate }}
                      </button>
                    }
                  </article>
                } @empty {
                  <p>{{ 'empty' | translate }}</p>
                }

                @if (selectedEvidence(); as targetEv) {
                  <form
                    class="payers-form payer-detail-form"
                    [formGroup]="correctionForm"
                    (ngSubmit)="submitCorrection(targetEv)"
                  >
                    <label
                      >{{ 'evidenceCorrectionRegion' | translate
                      }}<input pInputText formControlName="regionLabel" maxlength="120"
                    /></label>
                    <label>
                      {{ 'evidenceCorrectionText' | translate }}
                      <textarea
                        pInputText
                        formControlName="extractedText"
                        maxlength="8000"
                      ></textarea>
                    </label>
                    <label
                      >{{ 'evidenceCorrectionReason' | translate
                      }}<input pInputText formControlName="correctionReason" maxlength="240"
                    /></label>
                    <button pButton type="submit" [disabled]="busy()">
                      {{ 'submitEvidenceCorrection' | translate }}
                    </button>
                  </form>
                }
              }
            </section>

            <section class="payer-detail-section payer-rules-section">
              <h3>{{ 'ruleVersionsTitle' | translate }}</h3>
              @if (cleanPayerDocuments().length === 0) {
                <div class="notice" role="note">{{ 'ruleApprovalUnavailable' | translate }}</div>
              } @else if (api.payerManage()) {
                <label
                  >{{ 'sourceDocumentLabel' | translate
                  }}<select [formControl]="approvalDocumentId">
                    @for (doc of cleanPayerDocuments(); track doc.id) {
                      <option [value]="doc.id">{{ doc.sourceName }}</option>
                    }
                  </select></label
                >
              }
              @if (api.payerManage()) {
                <form
                  class="payers-form payer-rule-form"
                  [formGroup]="ruleForm"
                  (ngSubmit)="createRuleVersion(activePayer)"
                >
                  <label
                    >{{ 'ruleKind' | translate
                    }}<select formControlName="ruleKind">
                      @for (kind of ruleKinds; track kind) {
                        <option [value]="kind">{{ 'ruleKinds.' + kind | translate }}</option>
                      }
                    </select></label
                  >
                  <label
                    >{{ 'effectiveFrom' | translate
                    }}<input pInputText type="date" formControlName="effectiveFrom"
                  /></label>
                  <label
                    >{{ 'effectiveTo' | translate
                    }}<input pInputText type="date" formControlName="effectiveTo"
                  /></label>
                  @if (ruleForm.controls.ruleKind.value === 'TERMINOLOGY_MAPPING') {
                    <label
                      >{{ 'sourceCode' | translate
                      }}<input formControlName="sourceCode" maxlength="80"
                    /></label>
                    <label
                      >{{ 'sourceTerm' | translate
                      }}<input formControlName="sourceTerm" maxlength="240"
                    /></label>
                    <label
                      >{{ 'categoryKey' | translate
                      }}<input formControlName="categoryKey" maxlength="80"
                    /></label>
                  }
                  @if (ruleForm.controls.ruleKind.value === 'REQUIRED_DOCUMENTS') {
                    <label
                      >{{ 'requiredDocumentKeys' | translate
                      }}<input formControlName="documentKeys" maxlength="240"
                    /></label>
                  }
                  @if (ruleForm.controls.ruleKind.value === 'DEADLINE_RULE') {
                    <label
                      >{{ 'deadlineTrigger' | translate
                      }}<select formControlName="deadlineTrigger">
                        <option value="">{{ 'unknownValue' | translate }}</option>
                        <option value="CLAIM_SUBMISSION">
                          {{ 'deadlineTriggers.CLAIM_SUBMISSION' | translate }}
                        </option>
                        <option value="DENIAL_RECEIPT">
                          {{ 'deadlineTriggers.DENIAL_RECEIPT' | translate }}
                        </option>
                        <option value="DISCREPANCY_NOTICE">
                          {{ 'deadlineTriggers.DISCREPANCY_NOTICE' | translate }}
                        </option>
                        <option value="PAYER_INFORMATION_REQUEST">
                          {{ 'deadlineTriggers.PAYER_INFORMATION_REQUEST' | translate }}
                        </option>
                      </select></label
                    >
                    <label
                      >{{ 'deadlineOffset' | translate
                      }}<input type="number" formControlName="deadlineOffset" min="0" max="3650"
                    /></label>
                    <label
                      >{{ 'deadlineUnit' | translate
                      }}<select formControlName="deadlineUnit">
                        <option value="">{{ 'unknownValue' | translate }}</option>
                        <option value="DAYS">{{ 'deadlineUnits.DAYS' | translate }}</option>
                        <option value="BUSINESS_DAYS">
                          {{ 'deadlineUnits.BUSINESS_DAYS' | translate }}
                        </option>
                      </select></label
                    >
                    <label
                      >{{ 'deadlineDayBasis' | translate
                      }}<select formControlName="deadlineDayBasis">
                        <option value="">{{ 'unknownValue' | translate }}</option>
                        <option value="CALENDAR">
                          {{ 'deadlineDayBases.CALENDAR' | translate }}
                        </option>
                        <option value="BUSINESS">
                          {{ 'deadlineDayBases.BUSINESS' | translate }}
                        </option>
                      </select></label
                    >
                    <label
                      >{{ 'deadlineTimezone' | translate
                      }}<input formControlName="deadlineTimezone" maxlength="80"
                    /></label>
                    <label
                      >{{ 'deadlineCalendarSource' | translate
                      }}<input formControlName="deadlineCalendarSource" maxlength="80"
                    /></label>
                  }
                  @if (ruleForm.controls.ruleKind.value === 'WORKFLOW_STEPS') {
                    <label
                      >{{ 'workflowStepsLabel' | translate
                      }}<select multiple formControlName="workflowSteps">
                        @for (step of workflowStepKeys; track step) {
                          <option [value]="step">{{ 'workflowSteps.' + step | translate }}</option>
                        }
                      </select></label
                    >
                  }
                  <button pButton type="submit" [disabled]="busy()">
                    {{ 'createDraftRule' | translate }}
                  </button>
                </form>
              }
              @for (rule of ruleVersions(); track rule.id) {
                <article class="payer-rule-row">
                  <div class="payer-rule-title">
                    <h4>
                      v{{ rule.versionNumber }} · {{ 'ruleKinds.' + rule.ruleKind | translate }}
                    </h4>
                    <p-tag
                      [severity]="
                        rule.state === 'APPROVED'
                          ? 'success'
                          : rule.state === 'DRAFT'
                            ? 'warn'
                            : 'secondary'
                      "
                      [rounded]="true"
                      [value]="'ruleStates.' + rule.state | translate"
                    />
                  </div>
                  <p>
                    {{ 'deadlineBasisLabel' | translate }}:
                    {{ 'deadlineBases.' + rule.deadlineBasis | translate }} ·
                    {{ 'canDriveDueDateLabel' | translate }}:
                    {{ (rule.canDriveDueDate ? 'yes' : 'no') | translate }}
                  </p>
                  @for (mapping of rule.config.terminologyMappings; track mapping.sourceCode) {
                    <p>
                      {{ mapping.sourceCode }} — {{ mapping.sourceTerm }} →
                      {{ mapping.categoryKey }}
                    </p>
                  }
                  @if (
                    rule.state === 'DRAFT' && api.payerManage() && cleanPayerDocuments().length > 0
                  ) {
                    <div class="actions">
                      <button
                        pButton
                        type="button"
                        severity="secondary"
                        [outlined]="true"
                        [disabled]="busy() || !approvalDocumentId.value"
                        (click)="approveRuleVersion(activePayer, rule)"
                      >
                        {{ 'approveRuleVersion' | translate }}
                      </button>
                    </div>
                  }
                </article>
              } @empty {
                @if (!loading()) {
                  <p>{{ 'empty' | translate }}</p>
                }
              }
            </section>
          </div>
        </p-card>
      }
    }
  </main>`,
})
export class PayersPage {
  readonly api = inject(Api);
  readonly ruleKinds = ruleKinds;
  readonly workflowStepKeys = workflowStepKeys;
  readonly payers = signal<Payer[]>([]);
  readonly payerSearch = signal('');
  readonly filteredPayers = computed(() => {
    const query = this.payerSearch().trim().toLocaleLowerCase();
    if (!query) return this.payers();
    return this.payers().filter((payer) =>
      [payer.originalName, payer.code, payer.typeCode].some((value) =>
        value.toLocaleLowerCase().includes(query),
      ),
    );
  });
  readonly showPayerForm = signal(false);
  readonly showCategoryForm = signal(false);
  readonly categories = signal<DenialCategory[]>([]);
  readonly selectedPayer = signal<Payer | null>(null);
  readonly ruleVersions = signal<RuleVersion[]>([]);
  readonly payerDocuments = signal<DocumentMetadata[]>([]);
  readonly cleanPayerDocuments = computed(() =>
    this.payerDocuments().filter((d) => d.quarantineStatus === 'CLEAN'),
  );
  readonly selectedFile = signal<File | null>(null);
  readonly selectedDocument = signal<DocumentMetadata | null>(null);
  readonly evidenceVersions = signal<EvidenceVersion[]>([]);
  readonly selectedEvidence = signal<EvidenceVersion | null>(null);
  readonly approvalDocumentId = new FormControl('', { nonNullable: true });
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  private readonly fb = inject(FormBuilder);
  private readonly destroy = inject(DestroyRef);

  readonly correctionForm = this.fb.nonNullable.group({
    regionLabel: ['', [Validators.required, Validators.maxLength(120)]],
    extractedText: ['', [Validators.required, Validators.maxLength(8000)]],
    correctionReason: ['', [Validators.required, Validators.maxLength(240)]],
  });

  readonly payerForm = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(60)]],
    typeCode: ['IPM', [Validators.required, Validators.maxLength(60)]],
    originalName: ['', [Validators.required, Validators.maxLength(160)]],
  });

  readonly categoryForm = this.fb.nonNullable.group({
    key: ['', [Validators.required, Validators.maxLength(80)]],
    labelFr: ['', [Validators.required, Validators.maxLength(160)]],
    labelEn: ['', [Validators.required, Validators.maxLength(160)]],
  });

  readonly ruleForm = this.fb.group({
    ruleKind: new FormControl<RuleKind>('TERMINOLOGY_MAPPING', { nonNullable: true }),
    effectiveFrom: new FormControl('', { nonNullable: true }),
    effectiveTo: new FormControl('', { nonNullable: true }),
    sourceCode: new FormControl('', { nonNullable: true }),
    sourceTerm: new FormControl('', { nonNullable: true }),
    categoryKey: new FormControl('', { nonNullable: true }),
    documentKeys: new FormControl('', { nonNullable: true }),
    deadlineTrigger: new FormControl('DENIAL_RECEIPT', { nonNullable: true }),
    deadlineOffset: new FormControl<number | null>(null),
    deadlineUnit: new FormControl('DAYS', { nonNullable: true }),
    deadlineDayBasis: new FormControl('CALENDAR', { nonNullable: true }),
    deadlineTimezone: new FormControl('Africa/Dakar', { nonNullable: true }),
    deadlineCalendarSource: new FormControl('', { nonNullable: true }),
    workflowSteps: new FormControl<WorkflowStepKey[]>(['VERIFY_SOURCE_WORDING'], {
      nonNullable: true,
    }),
  });

  constructor() {
    if (this.api.payerRead()) this.loadAll();
  }

  updatePayerSearch(event: Event) {
    if (event.target instanceof HTMLInputElement) {
      this.payerSearch.set(event.target.value);
    }
  }

  clearSelectedPayer() {
    this.selectedPayer.set(null);
    this.selectedDocument.set(null);
    this.evidenceVersions.set([]);
    this.selectedEvidence.set(null);
  }

  loadAll() {
    this.loading.set(true);
    this.error.set('');
    forkJoin({
      payers: this.api.get<Page<Payer>>('/payers?page=0'),
      categories: this.api.get<Page<DenialCategory>>('/denial-categories?page=0'),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: ({ payers, categories }) => {
          this.payers.set(payers.items);
          this.categories.set(categories.items);
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  createPayer() {
    if (this.payerForm.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<Payer>('POST', '/payers', this.payerForm.getRawValue())
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.payers.update((list) => [...list, created]);
          this.payerForm.reset({ code: '', typeCode: 'IPM', originalName: '' });
          this.showPayerForm.set(false);
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  togglePayer(payer: Payer) {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<Payer>(
        'PATCH',
        `/payers/${payer.id}`,
        {
          code: payer.code,
          typeCode: payer.typeCode,
          originalName: payer.originalName,
          active: !payer.active,
        },
        payer.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.payers.update((list) => list.map((p) => (p.id === updated.id ? updated : p)));
          if (this.selectedPayer()?.id === updated.id) this.selectedPayer.set(updated);
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  createCategory() {
    if (this.categoryForm.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<DenialCategory>('POST', '/denial-categories', this.categoryForm.getRawValue())
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.categories.update((list) => [...list, created]);
          this.categoryForm.reset({ key: '', labelFr: '', labelEn: '' });
          this.showCategoryForm.set(false);
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  toggleCategory(category: DenialCategory) {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<DenialCategory>(
        'PATCH',
        `/denial-categories/${category.id}`,
        {
          key: category.key,
          labelFr: category.labelFr,
          labelEn: category.labelEn,
          active: !category.active,
        },
        category.version,
      )
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (updated) => {
          this.categories.update((list) => list.map((c) => (c.id === updated.id ? updated : c)));
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  selectPayer(payer: Payer) {
    this.selectedPayer.set(payer);
    this.selectedDocument.set(null);
    this.evidenceVersions.set([]);
    this.selectedEvidence.set(null);
    this.loading.set(true);
    this.error.set('');
    forkJoin({
      rules: this.api.get<Page<RuleVersion>>(`/payers/${payer.id}/rule-versions?page=0`),
      docs: this.api.get<Page<DocumentMetadata>>(`/payers/${payer.id}/documents?page=0`),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: ({ rules, docs }) => {
          this.ruleVersions.set(rules.items);
          this.payerDocuments.set(docs.items);
          const firstClean = docs.items.find((d) => d.quarantineStatus === 'CLEAN');
          this.approvalDocumentId.setValue(firstClean ? firstClean.id : '');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  onPayerFileSelected(event: Event) {
    const input = event.target as HTMLInputElement | null;
    this.selectedFile.set(input?.files?.[0] ?? null);
  }

  uploadPayerDocument(payer: Payer) {
    const file = this.selectedFile();
    if (!file) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .uploadDocument(file, { payerId: payer.id })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.payerDocuments.update((list) => [created, ...list]);
          if (created.quarantineStatus === 'CLEAN' && !this.approvalDocumentId.value) {
            this.approvalDocumentId.setValue(created.id);
          }
          this.selectedFile.set(null);
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
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
          this.payerDocuments.update((list) =>
            list.map((d) => (d.id === updated.id ? updated : d)),
          );
          if (updated.quarantineStatus === 'CLEAN' && !this.approvalDocumentId.value) {
            this.approvalDocumentId.setValue(updated.id);
          }
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
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
        error: (error) => this.error.set(errorKey(error)),
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
        error: (error) => this.error.set(errorKey(error)),
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
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  approveRuleVersion(payer: Payer, rule: RuleVersion) {
    const sourceDocumentId = this.approvalDocumentId.value;
    if (!sourceDocumentId) {
      this.error.set('errors.SOURCE_DOCUMENT_REQUIRED');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<RuleVersion>('POST', `/payer-rule-versions/${rule.id}/approval`, {
        sourceDocumentId,
      })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => {
          this.message.set('saved');
          this.selectPayer(payer);
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }

  createRuleVersion(payer: Payer) {
    const raw = this.ruleForm.getRawValue();
    const kind = raw.ruleKind;
    let config: RuleConfig;
    if (kind === 'TERMINOLOGY_MAPPING') {
      config = {
        terminologyMappings: [
          {
            sourceCode: raw.sourceCode,
            sourceTerm: raw.sourceTerm,
            categoryKey: raw.categoryKey,
          },
        ],
        requiredDocumentKeys: [],
        deadline: null,
        workflowSteps: [],
      };
    } else if (kind === 'REQUIRED_DOCUMENTS') {
      config = {
        terminologyMappings: [],
        requiredDocumentKeys: raw.documentKeys
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
        deadline: null,
        workflowSteps: [],
      };
    } else if (kind === 'DEADLINE_RULE') {
      config = {
        terminologyMappings: [],
        requiredDocumentKeys: [],
        deadline: {
          trigger: raw.deadlineTrigger || null,
          offsetValue: raw.deadlineOffset,
          unit: raw.deadlineUnit || null,
          dayBasis: raw.deadlineDayBasis || null,
          timezone: raw.deadlineTimezone || null,
          calendarSource: raw.deadlineCalendarSource || null,
        },
        workflowSteps: [],
      };
    } else {
      config = {
        terminologyMappings: [],
        requiredDocumentKeys: [],
        deadline: null,
        workflowSteps: raw.workflowSteps,
      };
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .mutate<RuleVersion>('POST', `/payers/${payer.id}/rule-versions`, {
        ruleKind: kind,
        effectiveFrom: raw.effectiveFrom || null,
        effectiveTo: raw.effectiveTo || null,
        config,
      })
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (created) => {
          this.ruleVersions.update((list) => [created, ...list]);
          this.message.set('saved');
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
