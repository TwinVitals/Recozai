import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Observable, catchError, of, switchMap, tap, throwError } from 'rxjs';
import { en } from './catalog';

export const roles = [
  'CLINIC_ADMIN',
  'BILLING_MANAGER',
  'BILLING_STAFF',
  'CLAIMS_REVIEWER',
  'MANAGEMENT_VIEWER',
] as const;
export type Role = (typeof roles)[number];
export interface Membership {
  id: string;
  clinicId: string;
  userId: string;
  login: string;
  active: boolean;
  version: number;
  roles: Role[];
}
export interface Profile {
  id: string;
  login: string;
  locale: string;
  clinicId: string | null;
  membershipId: string | null;
  roles: Role[];
  memberships: Membership[];
}
export interface Settings {
  id: string;
  name: string;
  locale: string;
  timezone: string;
  currency: string;
  fractionDigits: number;
  version: number;
}
export interface AuditEvent {
  id: string;
  action: keyof typeof en.events;
  occurredAt: string;
  actorId: string | null;
  subjectId: string | null;
}
export interface Payer {
  id: string;
  clinicId: string;
  code: string;
  typeCode: string;
  originalName: string;
  active: boolean;
  version: number;
}
export interface DenialCategory {
  id: string;
  clinicId: string;
  key: string;
  labelFr: string;
  labelEn: string;
  active: boolean;
  version: number;
}
export const ruleKinds = [
  'TERMINOLOGY_MAPPING',
  'REQUIRED_DOCUMENTS',
  'DEADLINE_RULE',
  'WORKFLOW_STEPS',
] as const;
export type RuleKind = (typeof ruleKinds)[number];
export type RuleState = keyof typeof en.ruleStates;
export type DeadlineBasis = keyof typeof en.deadlineBases;
export const workflowStepKeys = [
  'VERIFY_SOURCE_WORDING',
  'COLLECT_SUPPORTING_DOCUMENTS',
  'RECONCILE_EXPECTED_AMOUNT',
  'REQUEST_CLINICAL_CLARIFICATION',
  'PREPARE_APPEAL_DRAFT',
  'MANAGER_ESCALATION_REVIEW',
] as const;
export type WorkflowStepKey = (typeof workflowStepKeys)[number];
export interface TerminologyMapping {
  sourceCode: string;
  sourceTerm: string;
  categoryKey: string;
}
export interface DeadlineSpec {
  trigger: string | null;
  offsetValue: number | null;
  unit: string | null;
  dayBasis: string | null;
  timezone: string | null;
  calendarSource: string | null;
}
export interface RuleConfig {
  terminologyMappings: TerminologyMapping[];
  requiredDocumentKeys: string[];
  deadline: DeadlineSpec | null;
  workflowSteps: string[];
}
export interface RuleVersion {
  id: string;
  clinicId: string;
  payerId: string;
  versionNumber: number;
  state: RuleState;
  ruleKind: RuleKind;
  sourceDocumentId: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  deadlineBasis: DeadlineBasis;
  canDriveDueDate: boolean;
  approvalAvailable: boolean;
  config: RuleConfig;
  createdBy: string;
  createdAt: string;
}
export type ClaimState = keyof typeof en.claimStates;
export type RevisionSubmissionState = keyof typeof en.revisionSubmissionStates;
export const submissionEventKinds = ['SUBMISSION', 'RESUBMISSION', 'METADATA_CORRECTION'] as const;
export type SubmissionEventKind = (typeof submissionEventKinds)[number];
export type OccurrencePrecision = 'DATE' | 'INSTANT';
export type ClaimTransitionAction = 'VOID' | 'ARCHIVE' | 'REOPEN';
export interface Money {
  amount: string;
  currency: string;
}
export interface ClaimLineInput {
  serviceCode: string;
  description: string;
  amount: string;
}
export interface ClaimLine {
  id: string;
  lineNumber: number;
  serviceCode: string;
  description: string;
  submittedAmount: Money;
}
export interface ClaimRevision {
  id: string;
  revisionNumber: number;
  submittedAmount: Money;
  sourceDate: string;
  contentHash: string;
  changeReason: string | null;
  evidenceDocumentId: string | null;
  submissionState: RevisionSubmissionState;
  externalSubmissionCount: number;
  effectiveChannelCode: string | null;
  effectiveExternalSubmissionRef: string | null;
  effectiveOccurredOn: string | null;
  effectiveOccurredAt: string | null;
  effectiveOccurrencePrecision: OccurrencePrecision | null;
  lines: ClaimLine[];
  registeredBy: string;
  registeredAt: string;
}
export interface ClaimSummary {
  id: string;
  clinicId: string;
  payerId: string;
  externalReference: string;
  pseudonymousPatientRef: string;
  currency: string;
  currencyScale: number;
  state: ClaimState;
  draftSourceDate: string | null;
  activeRevisionId: string | null;
  activeRevisionNumber: number | null;
  activeSubmittedAmount: Money | null;
  activeRevisionSubmitted: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface ClaimDetail {
  id: string;
  clinicId: string;
  payerId: string;
  externalReference: string;
  pseudonymousPatientRef: string;
  currency: string;
  currencyScale: number;
  state: ClaimState;
  draftSourceDate: string | null;
  activeRevisionId: string | null;
  activeRevisionSubmitted: boolean;
  stateReason: string | null;
  draftLines: ClaimLine[];
  revisions: ClaimRevision[];
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}
export interface ClaimSubmissionEvent {
  id: string;
  clinicId: string;
  claimId: string;
  revisionId: string;
  revisionNumber: number;
  eventKind: SubmissionEventKind;
  channelCode: string;
  externalSubmissionRef: string;
  occurredOn: string;
  occurredAt: string | null;
  occurrencePrecision: OccurrencePrecision;
  evidenceDocumentId: string | null;
  resubmissionOfId: string | null;
  supersedesId: string | null;
  superseded: boolean;
  correctionReason: string | null;
  recordedBy: string;
  recordedAt: string;
}
export type QuarantineStatus = keyof typeof en.quarantineStatuses;
export type ScanStatus = keyof typeof en.scanStatuses;
export type QualityFlag = keyof typeof en.qualityFlags;
export interface DocumentMetadata {
  id: string;
  clinicId: string;
  claimId: string | null;
  payerId: string | null;
  sourceName: string;
  mimeType: string;
  byteCount: number;
  sha256Hash: string;
  quarantineStatus: QuarantineStatus;
  scanStatus: ScanStatus;
  scanDetail: string | null;
  pageCount: number | null;
  imageWidth: number | null;
  imageHeight: number | null;
  version: number;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string;
}
export interface EvidenceVersion {
  id: string;
  clinicId: string;
  documentId: string;
  versionNumber: number;
  pageNumber: number;
  regionLabel: string;
  extractedText: string;
  extractionMethod: string;
  qualityFlag: QualityFlag;
  reviewerCorrection: boolean;
  correctionReason: string | null;
  supersedesId: string | null;
  createdBy: string;
  createdAt: string;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}

export function errorKey(error: unknown): string {
  if (
    error instanceof HttpErrorResponse &&
    error.error &&
    typeof error.error.code === 'string' &&
    error.error.code in en.errors
  ) {
    return `errors.${error.error.code}`;
  }
  return 'errors.UNEXPECTED';
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  readonly user = signal<Profile | null>(null);
  readonly admin = computed(() => this.user()?.roles.includes('CLINIC_ADMIN') ?? false);
  readonly payerRead = computed(() => {
    const u = this.user();
    return (
      !!u?.clinicId &&
      u.roles.some((r) =>
        ['CLINIC_ADMIN', 'BILLING_MANAGER', 'BILLING_STAFF', 'CLAIMS_REVIEWER'].includes(r),
      )
    );
  });
  readonly payerManage = computed(
    () => !!this.user()?.clinicId && (this.user()?.roles.includes('BILLING_MANAGER') ?? false),
  );
  readonly claimRead = computed(() => {
    const u = this.user();
    return (
      !!u?.clinicId &&
      u.roles.some((r) => ['BILLING_MANAGER', 'BILLING_STAFF', 'CLAIMS_REVIEWER'].includes(r))
    );
  });
  readonly claimWrite = computed(() => {
    const u = this.user();
    return !!u?.clinicId && u.roles.some((r) => ['BILLING_MANAGER', 'BILLING_STAFF'].includes(r));
  });
  readonly claimManage = computed(
    () => !!this.user()?.clinicId && (this.user()?.roles.includes('BILLING_MANAGER') ?? false),
  );
  get<T>(path: string): Observable<T> {
    return this.http
      .get<T>(`/api/v1${path}`)
      .pipe(catchError((error: unknown) => this.fail(error)));
  }
  mutate<T>(
    method: 'POST' | 'PATCH' | 'DELETE',
    path: string,
    body?: object,
    version?: number,
    extraHeaders?: Record<string, string>,
  ): Observable<T> {
    return this.get<{ token: string }>('/session/csrf').pipe(
      switchMap(({ token }) => {
        let headers = new HttpHeaders({ 'X-XSRF-TOKEN': token });
        if (version !== undefined) headers = headers.set('If-Match', `"${version}"`);
        if (extraHeaders) {
          for (const [k, v] of Object.entries(extraHeaders)) {
            headers = headers.set(k, v);
          }
        }
        return this.http.request<T>(method, `/api/v1${path}`, { body, headers });
      }),
      catchError((error: unknown) => this.fail(error)),
    );
  }
  uploadDocument(
    file: File,
    parent: { claimId?: string; payerId?: string },
  ): Observable<DocumentMetadata> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (parent.claimId) form.append('claimId', parent.claimId);
    if (parent.payerId) form.append('payerId', parent.payerId);
    return this.get<{ token: string }>('/session/csrf').pipe(
      switchMap(({ token }) => {
        const headers = new HttpHeaders({ 'X-XSRF-TOKEN': token });
        return this.http.post<DocumentMetadata>('/api/v1/documents', form, { headers });
      }),
      catchError((error: unknown) => this.fail(error)),
    );
  }
  private fail(error: unknown): Observable<never> {
    if (
      error instanceof HttpErrorResponse &&
      error.status === 401 &&
      error.error?.code !== 'INVALID_CREDENTIALS'
    ) {
      this.user.set(null);
    }
    return throwError(() => error);
  }
  refresh(): Observable<Profile | null> {
    return this.get<Profile>('/me').pipe(
      tap((user) => this.user.set(user)),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.user.set(null);
          return of(null);
        }
        return throwError(() => error);
      }),
    );
  }
  login(login: string, password: string): Observable<Profile> {
    return this.mutate<Profile>('POST', '/session', { login, password }).pipe(
      tap((user) => this.user.set(user)),
    );
  }
  logout(): Observable<void> {
    return this.mutate<void>('DELETE', '/session').pipe(tap(() => this.user.set(null)));
  }
}
