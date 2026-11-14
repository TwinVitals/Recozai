import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, ClaimState } from './api.service';
import { en } from './catalog';

export type DenialCaseStatusKey = keyof typeof en.denialCaseStatuses;
export type AppealStatusKey = keyof typeof en.appealStatuses;
export type PaymentStatusKey = keyof typeof en.paymentStatuses;
export type RecoveryOutcomeKey = keyof typeof en.recoveryOutcomes;

export interface CaseEvidenceItem {
  id: string;
  documentName: string;
  pageLocator: string;
  extractedSnippet: string;
  confidencePct: number;
  provenance: 'EXTRACTED' | 'HUMAN_VERIFIED';
}

export interface CaseChecklistItem {
  id: string;
  stepKey: keyof typeof en.workflowSteps;
  done: boolean;
  requiresManager?: boolean;
}

export interface CaseTimelineItem {
  id: string;
  occurredAt: string;
  actor: string;
  summaryFr: string;
  summaryEn: string;
  provenance: 'VERIFIED_RECORD' | 'AI_ASSISTIVE' | 'HUMAN_APPROVED';
}

export interface DenialRecoveryCase {
  id: string;
  caseReference: string;
  claimExternalRef: string;
  pseudonymousPatientRef: string;
  payerCode: string;
  payerType: string;
  payerOriginalName: string;
  serviceDate: string;
  noticeDate: string;
  submittedAmountXof: number;
  deniedAmountXof: number;
  estimatedRecoverableXof: number;
  confirmedRecoveredXof: number;
  writtenOffXof: number;
  sourceReasonCode: string;
  originalReasonText: string;
  normalizedCategoryKey: string;
  normalizedLabelFr: string;
  normalizedLabelEn: string;
  ruleSourceCitation: string;
  deadlineDate: string | null;
  deadlineBasis: 'APPROVED_SOURCED' | 'PARAMETERIZED_DRAFT' | 'UNKNOWN';
  claimStatus: ClaimState;
  caseStatus: DenialCaseStatusKey;
  appealStatus: AppealStatusKey;
  paymentStatus: PaymentStatusKey;
  recoveryOutcome: RecoveryOutcomeKey;
  aiConfidencePct: number;
  aiRationaleFr: string;
  aiRationaleEn: string;
  aiMissingEvidenceFr: string;
  aiMissingEvidenceEn: string;
  appealLocale: 'fr-SN' | 'en';
  appealSubject: string;
  appealBody: string;
  appealApprovedBy: string | null;
  appealApprovedAt: string | null;
  submissionChannel: string | null;
  submissionExternalRef: string | null;
  submissionDate: string | null;
  isSyntheticDemo: boolean;
  evidence: CaseEvidenceItem[];
  checklist: CaseChecklistItem[];
  timeline: CaseTimelineItem[];
}

const INITIAL_DEMO_CASES: DenialRecoveryCase[] = [
  {
    id: 'demo-case-10472',
    caseReference: 'REC-SN-2026-041',
    claimExternalRef: 'CLM-2026-10472',
    pseudonymousPatientRef: 'PAT-SN-8841',
    payerCode: 'IPM_DAKAR_01',
    payerType: 'IPM',
    payerOriginalName: 'IPM Inter-Entreprises de Dakar',
    serviceDate: '2026-02-02',
    noticeDate: '2026-02-09',
    submittedAmountXof: 185000,
    deniedAmountXof: 185000,
    estimatedRecoverableXof: 185000,
    confirmedRecoveredXof: 0,
    writtenOffXof: 0,
    sourceReasonCode: 'REJ-IPM-09',
    originalReasonText:
      'REJ-IPM-09 : Bordereau de facturation non contresigné par le praticien traitant et bulletin de prise en charge n° BPEC-441 illisible en page 2.',
    normalizedCategoryKey: 'missing-attachment',
    normalizedLabelFr: 'Pièce justificative ou signature absente',
    normalizedLabelEn: 'Missing supporting attachment or signature',
    ruleSourceCitation:
      'Convention IPM Dakar 2026 · Art. 12 (Délai 30j calendaires · v1 Approuvée)',
    deadlineDate: '2026-03-11',
    deadlineBasis: 'APPROVED_SOURCED',
    claimStatus: 'SUBMITTED',
    caseStatus: 'ACTION_REQUIRED',
    appealStatus: 'DRAFT',
    paymentStatus: 'UNPAID_DISPUTED',
    recoveryOutcome: 'PENDING',
    aiConfidencePct: 94,
    aiRationaleFr:
      'Correspondance exacte avec le code REJ-IPM-09 (Convention IPM Dakar Art. 12). Le rejet est purement administratif : l’acte est couvert à 80 % sous réserve de fournir le bordereau signé et un scan lisible du bulletin BPEC-441.',
    aiRationaleEn:
      'Exact match with code REJ-IPM-09 (IPM Dakar Convention Art. 12). Administrative rejection only: service is covered subject to countersigned bordereau and legible scan of BPEC-441.',
    aiMissingEvidenceFr:
      'Scan haute résolution de la page 2 du bulletin BPEC-441 + cachet praticien',
    aiMissingEvidenceEn: 'High-resolution scan of BPEC-441 page 2 + practitioner stamp',
    appealLocale: 'fr-SN',
    appealSubject:
      'Recours gracieux et régularisation documentaire — Facture CLM-2026-10472 (Réf. PAT-SN-8841)',
    appealBody: `Madame, Monsieur le Médecin-Conseil de l'IPM Inter-Entreprises de Dakar,

Nous faisons suite à votre notification de rejet portant la référence REJ-IPM-09 du 2026-02-09 relative à la demande CLM-2026-10472 d'un montant de 185 000 XOF (Réf. bénéficiaire pseudonymisée : PAT-SN-8841).

Motif initial invoqué (source) : « REJ-IPM-09 : Bordereau de facturation non contresigné par le praticien traitant et bulletin de prise en charge n° BPEC-441 illisible en page 2. »

Conformément à l'Article 12 de la Convention IPM Dakar 2026 (délai de contestation de 30 jours calendaires), nous vous prions de trouver ci-joint :
1. Le bordereau R1 dûment signé et cacheté par le praticien (Pièce vérifiée p.1 · REGION_SIGN_1) ;
2. La copie lisible intégrale du bulletin de prise en charge n° BPEC-441.

Nous vous remercions de bien vouloir procéder à la réévaluation et au règlement de la somme de 185 000 XOF.`,
    appealApprovedBy: null,
    appealApprovedAt: null,
    submissionChannel: null,
    submissionExternalRef: null,
    submissionDate: null,
    isSyntheticDemo: true,
    evidence: [
      {
        id: 'ev-10472-1',
        documentName: 'bordereau-signe-10472.pdf',
        pageLocator: 'p.1 · REGION_SIGN_1',
        extractedSnippet:
          'Bordereau de soins CLM-2026-10472 — Montant total : 185 000 XOF — Cachet et signature praticien vérifiés.',
        confidencePct: 96,
        provenance: 'HUMAN_VERIFIED',
      },
      {
        id: 'ev-10472-2',
        documentName: 'bulletin-bpec-441-rescan.pdf',
        pageLocator: 'p.2 · PAGE_2_BPEC',
        extractedSnippet:
          'Bulletin de prise en charge BPEC-441 — Taux de couverture : 100 % tarif conventionné (185 000 XOF).',
        confidencePct: 89,
        provenance: 'EXTRACTED',
      },
    ],
    checklist: [
      { id: 'chk-1', stepKey: 'VERIFY_SOURCE_WORDING', done: true },
      { id: 'chk-2', stepKey: 'COLLECT_SUPPORTING_DOCUMENTS', done: true },
      { id: 'chk-3', stepKey: 'PREPARE_APPEAL_DRAFT', done: true },
      { id: 'chk-4', stepKey: 'MANAGER_ESCALATION_REVIEW', done: false, requiresManager: true },
    ],
    timeline: [
      {
        id: 'tl-1',
        occurredAt: '2026-02-09T10:15:00Z',
        actor: 'system-intake',
        summaryFr: 'Notification de rejet REJ-IPM-09 enregistrée (185 000 XOF contestés).',
        summaryEn: 'Rejection notice REJ-IPM-09 recorded (185,000 XOF disputed).',
        provenance: 'VERIFIED_RECORD',
      },
      {
        id: 'tl-2',
        occurredAt: '2026-02-10T09:20:00Z',
        actor: 'ai-assistant-v1',
        summaryFr:
          'Classification assistée proposée : Pièce justificative absente (Confiance 94 % · Règle v1).',
        summaryEn:
          'Assistive classification proposed: Missing attachment (Confidence 94% · Rule v1).',
        provenance: 'AI_ASSISTIVE',
      },
    ],
  },
  {
    id: 'demo-case-10471',
    caseReference: 'REC-SN-2026-039',
    claimExternalRef: 'CLM-2026-10471',
    pseudonymousPatientRef: 'PAT-SN-7712',
    payerCode: 'MUT_SANTE_SN',
    payerType: 'MUTUELLE',
    payerOriginalName: 'Mutuelle de Santé des Agents du Sénégal',
    serviceDate: '2026-01-22',
    noticeDate: '2026-01-28',
    submittedAmountXof: 140000,
    deniedAmountXof: 140000,
    estimatedRecoverableXof: 140000,
    confirmedRecoveredXof: 140000,
    writtenOffXof: 0,
    sourceReasonCode: 'MUT-TAR-04',
    originalReasonText:
      'MUT-TAR-04 : Écart tarifaire sur acte d’échographie abdominale (code IMG-ECHO) faute de fiche de liaison préalable.',
    normalizedCategoryKey: 'tariff-discrepancy',
    normalizedLabelFr: 'Écart tarifaire ou barème conventionnel',
    normalizedLabelEn: 'Tariff or contractual fee discrepancy',
    ruleSourceCitation: 'Barème Mutuelle 2026 · Annexe B (v2 Approuvée)',
    deadlineDate: '2026-02-27',
    deadlineBasis: 'APPROVED_SOURCED',
    claimStatus: 'SUBMITTED',
    caseStatus: 'RECOVERED_FULL',
    appealStatus: 'SUBMITTED',
    paymentStatus: 'RECOVERED_FULL',
    recoveryOutcome: 'RECOVERED_FULL',
    aiConfidencePct: 91,
    aiRationaleFr:
      'La fiche de liaison préalable a été retrouvée dans le dossier d’admission et transmise avec le recours approuvé.',
    aiRationaleEn:
      'Prior liaison sheet was located in the admission file and submitted with the approved appeal.',
    aiMissingEvidenceFr: 'Aucune pièce manquante — dossier complet',
    aiMissingEvidenceEn: 'No missing evidence — file complete',
    appealLocale: 'fr-SN',
    appealSubject: 'Contestations d’écart tarifaire MUT-TAR-04 — Facture CLM-2026-10471',
    appealBody:
      'Recours approuvé et transmis avec la fiche de liaison signée. Règlement intégral de 140 000 XOF reçu par virement.',
    appealApprovedBy: 'billing-manager-dakar',
    appealApprovedAt: '2026-02-01T14:00:00Z',
    submissionChannel: 'PORTAIL_IPM',
    submissionExternalRef: 'BORD-REC-2026-88',
    submissionDate: '2026-02-02',
    isSyntheticDemo: true,
    evidence: [
      {
        id: 'ev-10471-1',
        documentName: 'fiche-liaison-10471.pdf',
        pageLocator: 'p.1 · HEADER',
        extractedSnippet:
          'Autorisation préalable IMG-ECHO accordée le 2026-01-21 — Tarif conventionné : 140 000 XOF.',
        confidencePct: 98,
        provenance: 'HUMAN_VERIFIED',
      },
    ],
    checklist: [
      { id: 'chk-21', stepKey: 'VERIFY_SOURCE_WORDING', done: true },
      { id: 'chk-22', stepKey: 'COLLECT_SUPPORTING_DOCUMENTS', done: true },
      { id: 'chk-23', stepKey: 'RECONCILE_EXPECTED_AMOUNT', done: true },
      { id: 'chk-24', stepKey: 'PREPARE_APPEAL_DRAFT', done: true },
      { id: 'chk-25', stepKey: 'MANAGER_ESCALATION_REVIEW', done: true, requiresManager: true },
    ],
    timeline: [
      {
        id: 'tl-21',
        occurredAt: '2026-02-01T14:00:00Z',
        actor: 'billing-manager-dakar',
        summaryFr: 'Recours v1 approuvé humainement pour soumission externe.',
        summaryEn: 'Appeal v1 human-approved for external submission.',
        provenance: 'HUMAN_APPROVED',
      },
      {
        id: 'tl-22',
        occurredAt: '2026-02-08T11:30:00Z',
        actor: 'billing-manager-dakar',
        summaryFr:
          'Règlement payeur confirmé au journal financier : 140 000 XOF (Réf. VIR-MUT-902).',
        summaryEn: 'Confirmed payer recovery recorded in ledger: 140,000 XOF (Ref VIR-MUT-902).',
        provenance: 'VERIFIED_RECORD',
      },
    ],
  },
  {
    id: 'demo-case-10470',
    caseReference: 'REC-SN-2026-044',
    claimExternalRef: 'CLM-2026-10470',
    pseudonymousPatientRef: 'PAT-SN-6309',
    payerCode: 'CNAM_CMU_SN',
    payerType: 'CMU',
    payerOriginalName: 'Caisse Nationale / Schéma CMU Sénégal',
    serviceDate: '2026-02-04',
    noticeDate: '2026-02-11',
    submittedAmountXof: 95000,
    deniedAmountXof: 65000,
    estimatedRecoverableXof: 65000,
    confirmedRecoveredXof: 0,
    writtenOffXof: 0,
    sourceReasonCode: 'CMU-ELIG-02',
    originalReasonText:
      'CMU-ELIG-02 : Numéro d’attestation d’affiliation bénéficiaire absent sur la feuille de soins.',
    normalizedCategoryKey: 'beneficiary-data',
    normalizedLabelFr: 'Référence d’affiliation bénéficiaire incomplète',
    normalizedLabelEn: 'Incomplete beneficiary affiliation reference',
    ruleSourceCitation: 'Guide CMU Sénégal · Section 4 (Règle en brouillon — échéance non sourcée)',
    deadlineDate: null,
    deadlineBasis: 'UNKNOWN',
    claimStatus: 'SUBMITTED',
    caseStatus: 'AWAITING_APPROVAL',
    appealStatus: 'AWAITING_APPROVAL',
    paymentStatus: 'UNPAID_DISPUTED',
    recoveryOutcome: 'PENDING',
    aiConfidencePct: 88,
    aiRationaleFr:
      'Attestation d’affiliation retrouvée et rattachée. Aucune règle de délai approuvée n’existe pour ce payeur : l’échéance reste « Inconnue » sans supposition automatique.',
    aiRationaleEn:
      'Affiliation certificate attached. No approved deadline rule exists for this payer: due date remains "Unknown" without fabrication.',
    aiMissingEvidenceFr: 'Attestation CMU jointe — en attente de validation du responsable',
    aiMissingEvidenceEn: 'CMU certificate attached — awaiting Billing Manager approval',
    appealLocale: 'fr-SN',
    appealSubject:
      'Transmission du numéro d’attestation CMU — Régularisation facture CLM-2026-10470',
    appealBody: `Madame, Monsieur,

Suite au rejet partiel CMU-ELIG-02 du 2026-02-11 portant sur un montant de 65 000 XOF (facture CLM-2026-10470, réf. patient pseudonymisée PAT-SN-6309), nous vous transmettons l'attestation d'affiliation vérifiée en pièce jointe.

Nous sollicitons la levée de la suspension et le règlement du solde de 65 000 XOF.`,
    appealApprovedBy: null,
    appealApprovedAt: null,
    submissionChannel: null,
    submissionExternalRef: null,
    submissionDate: null,
    isSyntheticDemo: true,
    evidence: [
      {
        id: 'ev-10470-1',
        documentName: 'attestation-cmu-6309.pdf',
        pageLocator: 'p.1 · CARD_REF',
        extractedSnippet:
          'Attestation CMU active — Période de validité : 2026-01-01 au 2026-12-31.',
        confidencePct: 93,
        provenance: 'HUMAN_VERIFIED',
      },
    ],
    checklist: [
      { id: 'chk-31', stepKey: 'VERIFY_SOURCE_WORDING', done: true },
      { id: 'chk-32', stepKey: 'COLLECT_SUPPORTING_DOCUMENTS', done: true },
      { id: 'chk-33', stepKey: 'PREPARE_APPEAL_DRAFT', done: true },
      { id: 'chk-34', stepKey: 'MANAGER_ESCALATION_REVIEW', done: false, requiresManager: true },
    ],
    timeline: [
      {
        id: 'tl-31',
        occurredAt: '2026-02-11T16:00:00Z',
        actor: 'billing-staff-dakar',
        summaryFr: 'Projet de recours soumis à l’approbation du responsable facturation.',
        summaryEn: 'Appeal draft submitted for Billing Manager approval.',
        provenance: 'VERIFIED_RECORD',
      },
    ],
  },
];

@Injectable({ providedIn: 'root' })
export class RecoveryWorkspaceService {
  private readonly api = inject(Api);

  readonly showSyntheticDemo = signal(true);
  readonly searchQuery = signal('');
  readonly selectedTimeframe = signal<'7D' | '30D' | '90D'>('30D');
  readonly casesState = signal<DenialRecoveryCase[]>(INITIAL_DEMO_CASES);
  readonly aiChatLog = signal<
    { id: string; question: string; answerFr: string; answerEn: string; timestamp: string }[]
  >([]);

  readonly visibleCases = computed(() => {
    const showDemo = this.showSyntheticDemo();
    return this.casesState().filter((c) => showDemo || !c.isSyntheticDemo);
  });

  toggleSyntheticDemo() {
    this.showSyntheticDemo.update((v) => !v);
  }

  createDenialCase(input: {
    claimExternalRef: string;
    pseudonymousPatientRef: string;
    payerCode: string;
    payerType: string;
    payerOriginalName: string;
    sourceReasonCode: string;
    originalReasonText: string;
    normalizedCategoryKey: string;
    normalizedLabelFr: string;
    normalizedLabelEn: string;
    noticeDate: string;
    deniedAmountXof: number;
    estimatedRecoverableXof: number;
    hasApprovedRule: boolean;
  }): DenialRecoveryCase {
    const seq = this.casesState().length + 45;
    const newCase: DenialRecoveryCase = {
      id: `case-${Date.now()}`,
      caseReference: `REC-SN-2026-0${seq}`,
      claimExternalRef: input.claimExternalRef,
      pseudonymousPatientRef: input.pseudonymousPatientRef,
      payerCode: input.payerCode,
      payerType: input.payerType,
      payerOriginalName: input.payerOriginalName,
      serviceDate: input.noticeDate,
      noticeDate: input.noticeDate,
      submittedAmountXof: input.deniedAmountXof,
      deniedAmountXof: input.deniedAmountXof,
      estimatedRecoverableXof: input.estimatedRecoverableXof,
      confirmedRecoveredXof: 0,
      writtenOffXof: 0,
      sourceReasonCode: input.sourceReasonCode,
      originalReasonText: input.originalReasonText,
      normalizedCategoryKey: input.normalizedCategoryKey,
      normalizedLabelFr: input.normalizedLabelFr,
      normalizedLabelEn: input.normalizedLabelEn,
      ruleSourceCitation: input.hasApprovedRule
        ? `${input.payerOriginalName} · Règle sourcée approuvée`
        : 'Aucune règle de délai approuvée (échéance non supposée)',
      deadlineDate: null,
      deadlineBasis: input.hasApprovedRule ? 'APPROVED_SOURCED' : 'UNKNOWN',
      claimStatus: 'SUBMITTED',
      caseStatus: 'NEW',
      appealStatus: 'NONE',
      paymentStatus: 'UNPAID_DISPUTED',
      recoveryOutcome: 'PENDING',
      aiConfidencePct: 86,
      aiRationaleFr: `Rejet enregistré sous le code ${input.sourceReasonCode}. Vérifiez les pièces justificatives avant de générer le projet de recours.`,
      aiRationaleEn: `Rejection recorded under code ${input.sourceReasonCode}. Verify supporting documents before generating the appeal draft.`,
      aiMissingEvidenceFr: 'Pièce justificative de réclamation à vérifier humainement',
      aiMissingEvidenceEn: 'Supporting claim evidence requires human verification',
      appealLocale: 'fr-SN',
      appealSubject: `Recours — Facture ${input.claimExternalRef} (${input.sourceReasonCode})`,
      appealBody: '',
      appealApprovedBy: null,
      appealApprovedAt: null,
      submissionChannel: null,
      submissionExternalRef: null,
      submissionDate: null,
      isSyntheticDemo: false,
      evidence: [
        {
          id: `ev-${Date.now()}`,
          documentName: `notification-${input.claimExternalRef.toLowerCase()}.pdf`,
          pageLocator: 'p.1 · REJECTION_HEADER',
          extractedSnippet: input.originalReasonText,
          confidencePct: 90,
          provenance: 'EXTRACTED',
        },
      ],
      checklist: [
        { id: `chk-${Date.now()}-1`, stepKey: 'VERIFY_SOURCE_WORDING', done: true },
        { id: `chk-${Date.now()}-2`, stepKey: 'COLLECT_SUPPORTING_DOCUMENTS', done: false },
        { id: `chk-${Date.now()}-3`, stepKey: 'PREPARE_APPEAL_DRAFT', done: false },
        {
          id: `chk-${Date.now()}-4`,
          stepKey: 'MANAGER_ESCALATION_REVIEW',
          done: false,
          requiresManager: true,
        },
      ],
      timeline: [
        {
          id: `tl-${Date.now()}`,
          occurredAt: new Date().toISOString(),
          actor: this.api.user()?.login ?? 'staff',
          summaryFr: `Dossier de rejet créé pour ${input.claimExternalRef} (${input.deniedAmountXof} XOF).`,
          summaryEn: `Denial recovery case created for ${input.claimExternalRef} (${input.deniedAmountXof} XOF).`,
          provenance: 'VERIFIED_RECORD',
        },
      ],
    };
    this.casesState.update((list) => [newCase, ...list]);
    return newCase;
  }

  verifyEvidence(caseId: string, evidenceId: string) {
    const actor = this.api.user()?.login ?? 'reviewer';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        const evidence = c.evidence.map((ev) =>
          ev.id === evidenceId ? { ...ev, provenance: 'HUMAN_VERIFIED' as const } : ev,
        );
        const allVerified = evidence.every((ev) => ev.provenance === 'HUMAN_VERIFIED');
        return {
          ...c,
          caseStatus:
            c.caseStatus === 'NEW' || c.caseStatus === 'ACTION_REQUIRED'
              ? allVerified
                ? 'ANALYSIS_VERIFICATION'
                : c.caseStatus
              : c.caseStatus,
          evidence,
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: `Preuve vérifiée humainement (${evidenceId}).`,
              summaryEn: `Evidence human-verified (${evidenceId}).`,
              provenance: 'HUMAN_APPROVED',
            },
            ...c.timeline,
          ],
        };
      }),
    );
  }

  toggleChecklistItem(caseId: string, itemId: string) {
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          checklist: c.checklist.map((item) =>
            item.id === itemId ? { ...item, done: !item.done } : item,
          ),
        };
      }),
    );
  }

  generateAiAppealDraft(caseId: string, locale: 'fr-SN' | 'en') {
    const actor = this.api.user()?.login ?? 'staff';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        const hasUnverified = c.evidence.some((e) => e.provenance !== 'HUMAN_VERIFIED');
        const placeholderFr = hasUnverified
          ? '\n\n[PLACEHOLDER: Confirmer la référence de la pièce justificative vérifiée avant approbation]'
          : '';
        const placeholderEn = hasUnverified
          ? '\n\n[PLACEHOLDER: Confirm verified supporting document reference before manager approval]'
          : '';
        const subject =
          locale === 'fr-SN'
            ? `Recours administratif — Demande ${c.claimExternalRef} (${c.sourceReasonCode})`
            : `Administrative Appeal — Claim ${c.claimExternalRef} (${c.sourceReasonCode})`;
        const body =
          locale === 'fr-SN'
            ? `À l'attention du Service Médical et Facturation — ${c.payerOriginalName} (${c.payerCode})\n\nObjet : Contestation du rejet ${c.sourceReasonCode} sur la demande ${c.claimExternalRef} (Réf. patient pseudonymisée : ${c.pseudonymousPatientRef})\nMontant contesté : ${c.deniedAmountXof} XOF\n\nMotif original communiqué par vos services :\n« ${c.originalReasonText} »\n\nÉléments justificatifs vérifiés :\n${c.evidence.map((e) => `- ${e.documentName} (${e.pageLocator}) : ${e.extractedSnippet}`).join('\n')}\n\nBase réglementaire / conventionnelle : ${c.ruleSourceCitation}${placeholderFr}\n\nNous vous prions de bien vouloir réexaminer ce dossier et procéder au règlement de ${c.deniedAmountXof} XOF.`
            : `To the Medical & Claims Review Department — ${c.payerOriginalName} (${c.payerCode})\n\nSubject: Reconsideration of rejection ${c.sourceReasonCode} on claim ${c.claimExternalRef} (Pseudonymous patient ref: ${c.pseudonymousPatientRef})\nDisputed amount: ${c.deniedAmountXof} XOF\n\nOriginal payer rejection wording (verbatim source):\n"${c.originalReasonText}"\n\nVerified supporting evidence:\n${c.evidence.map((e) => `- ${e.documentName} (${e.pageLocator}): ${e.extractedSnippet}`).join('\n')}\n\nRule / convention basis: ${c.ruleSourceCitation}${placeholderEn}\n\nWe kindly request reconsideration and settlement of the disputed ${c.deniedAmountXof} XOF balance.`;
        return {
          ...c,
          appealLocale: locale,
          appealSubject: subject,
          appealBody: body,
          appealStatus: 'DRAFT',
          caseStatus: 'APPEAL_DRAFT',
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: `Brouillon de recours assisté par IA généré en ${locale === 'fr-SN' ? 'français' : 'anglais'} (requiert revue humaine).`,
              summaryEn: `AI assistive appeal draft generated in ${locale === 'fr-SN' ? 'French' : 'English'} (requires human review).`,
              provenance: 'AI_ASSISTIVE',
            },
            ...c.timeline,
          ],
        };
      }),
    );
  }

  saveAppealDraft(
    caseId: string,
    draft: { locale: 'fr-SN' | 'en'; subject: string; body: string },
  ) {
    const actor = this.api.user()?.login ?? 'staff';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          appealLocale: draft.locale,
          appealSubject: draft.subject,
          appealBody: draft.body,
          appealStatus: 'DRAFT',
          caseStatus: 'APPEAL_DRAFT',
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: 'Brouillon de recours modifié et enregistré.',
              summaryEn: 'Appeal draft edited and saved.',
              provenance: 'VERIFIED_RECORD',
            },
            ...c.timeline,
          ],
        };
      }),
    );
  }

  submitAppealForApproval(caseId: string) {
    const actor = this.api.user()?.login ?? 'staff';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          appealStatus: 'AWAITING_APPROVAL',
          caseStatus: 'AWAITING_APPROVAL',
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: 'Projet de recours soumis pour approbation du responsable facturation.',
              summaryEn: 'Appeal draft submitted for Billing Manager approval.',
              provenance: 'VERIFIED_RECORD',
            },
            ...c.timeline,
          ],
        };
      }),
    );
  }

  approveAppeal(caseId: string): boolean {
    const target = this.casesState().find((c) => c.id === caseId);
    if (!target || !this.api.claimManage()) return false;
    if (target.appealBody.includes('[PLACEHOLDER') || !target.appealBody.trim()) {
      return false;
    }
    const actor = this.api.user()?.login ?? 'billing-manager';
    const now = new Date().toISOString();
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          appealStatus: 'APPROVED',
          caseStatus: 'APPROVED_FOR_SUBMISSION',
          appealApprovedBy: actor,
          appealApprovedAt: now,
          checklist: c.checklist.map((chk) =>
            chk.stepKey === 'MANAGER_ESCALATION_REVIEW' ? { ...chk, done: true } : chk,
          ),
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: now,
              actor,
              summaryFr: `Recours approuvé humainement par ${actor} pour envoi externe.`,
              summaryEn: `Appeal human-approved by ${actor} for external submission.`,
              provenance: 'HUMAN_APPROVED',
            },
            ...c.timeline,
          ],
        };
      }),
    );
    return true;
  }

  returnAppealForRevision(caseId: string) {
    const actor = this.api.user()?.login ?? 'billing-manager';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          appealStatus: 'RETURNED',
          caseStatus: 'APPEAL_DRAFT',
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: 'Brouillon de recours renvoyé pour correction.',
              summaryEn: 'Appeal draft returned for revision.',
              provenance: 'VERIFIED_RECORD',
            },
            ...c.timeline,
          ],
        };
      }),
    );
  }

  recordAppealSubmission(
    caseId: string,
    submission: { channelCode: string; externalRef: string; occurredOn: string },
  ): boolean {
    const target = this.casesState().find((c) => c.id === caseId);
    if (!target || target.appealStatus !== 'APPROVED') return false;
    const actor = this.api.user()?.login ?? 'staff';
    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          appealStatus: 'SUBMITTED',
          caseStatus: 'SUBMITTED_TO_PAYER',
          submissionChannel: submission.channelCode,
          submissionExternalRef: submission.externalRef,
          submissionDate: submission.occurredOn,
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: `Envoi externe du recours enregistré (${submission.channelCode} · Réf. ${submission.externalRef} le ${submission.occurredOn}).`,
              summaryEn: `External appeal submission recorded (${submission.channelCode} · Ref ${submission.externalRef} on ${submission.occurredOn}).`,
              provenance: 'VERIFIED_RECORD',
            },
            ...c.timeline,
          ],
        };
      }),
    );
    return true;
  }

  recordRecoveryPayment(
    caseId: string,
    recoveredAmountXof: number,
    writeOffRemainder: boolean,
  ): boolean {
    const target = this.casesState().find((c) => c.id === caseId);
    if (!target || recoveredAmountXof < 0 || recoveredAmountXof > target.deniedAmountXof) {
      return false;
    }
    const actor = this.api.user()?.login ?? 'billing-manager';
    const remainder = Math.max(0, target.deniedAmountXof - recoveredAmountXof);
    const writtenOff = writeOffRemainder ? remainder : 0;
    const isFull = recoveredAmountXof === target.deniedAmountXof && target.deniedAmountXof > 0;
    const isPartial = recoveredAmountXof > 0 && recoveredAmountXof < target.deniedAmountXof;
    const paymentStatus: PaymentStatusKey = isFull
      ? 'RECOVERED_FULL'
      : isPartial
        ? 'PARTIALLY_RECOVERED'
        : writeOffRemainder
          ? 'WRITTEN_OFF'
          : 'UNPAID_DISPUTED';
    const recoveryOutcome: RecoveryOutcomeKey = isFull
      ? 'RECOVERED_FULL'
      : isPartial
        ? 'RECOVERED_PARTIAL'
        : writeOffRemainder
          ? 'UPHELD_WRITTEN_OFF'
          : 'PENDING';
    const caseStatus: DenialCaseStatusKey = isFull
      ? 'RECOVERED_FULL'
      : isPartial
        ? 'RECOVERED_PARTIAL'
        : writeOffRemainder
          ? 'CLOSED'
          : target.caseStatus;

    this.casesState.update((list) =>
      list.map((c) => {
        if (c.id !== caseId) return c;
        return {
          ...c,
          confirmedRecoveredXof: recoveredAmountXof,
          writtenOffXof: writtenOff,
          paymentStatus,
          recoveryOutcome,
          caseStatus,
          timeline: [
            {
              id: `tl-${Date.now()}`,
              occurredAt: new Date().toISOString(),
              actor,
              summaryFr: `Règlement payeur enregistré au journal : ${recoveredAmountXof} XOF recouvrés${writtenOff > 0 ? ` (${writtenOff} XOF passés en perte)` : ''}.`,
              summaryEn: `Payer settlement recorded in ledger: ${recoveredAmountXof} XOF recovered${writtenOff > 0 ? ` (${writtenOff} XOF written off)` : ''}.`,
              provenance: 'VERIFIED_RECORD',
            },
            ...c.timeline,
          ],
        };
      }),
    );
    return true;
  }

  askAiAssistant(question: string) {
    const trimmed = question.trim();
    if (!trimmed) return;
    const openCases = this.visibleCases().filter((c) => c.recoveryOutcome === 'PENDING');
    const totalDisputed = openCases.reduce((sum, c) => sum + c.deniedAmountXof, 0);
    const answerFr = `[Suggestion assistée par IA · Basée sur ${openCases.length} dossier(s) actif(s) totalisant ${totalDisputed.toLocaleString('fr-SN')} XOF] : Pour la question « ${trimmed} », priorisez le dossier CLM-2026-10472 (IPM Dakar, 185 000 XOF, échéance sourcée le 2026-03-11) et vérifiez la page 2 du bulletin BPEC-441 avant l'approbation du responsable facturation.`;
    const answerEn = `[AI Assistive Response · Based on ${openCases.length} active case(s) totaling ${totalDisputed.toLocaleString('en-US')} XOF]: Regarding "${trimmed}", prioritize claim CLM-2026-10472 (IPM Dakar, 185,000 XOF, sourced deadline 2026-03-11) and verify page 2 of BPEC-441 before Billing Manager approval.`;
    this.aiChatLog.update((log) => [
      {
        id: `qa-${Date.now()}`,
        question: trimmed,
        answerFr,
        answerEn,
        timestamp: new Date().toISOString(),
      },
      ...log,
    ]);
  }
}
