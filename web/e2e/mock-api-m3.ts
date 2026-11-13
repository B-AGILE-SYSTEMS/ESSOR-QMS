import { Route } from '@playwright/test';
import { LABELS, MockApi, PREFIX, Rec, USERS, WORKFLOWS } from './mock-api';

/**
 * API simulée M3 (docs/pilotage/cartes/M3-contrat.md) : référentiels, audits, gestion documentaire.
 * Étend l'API M1/M2. Formes de réponse non fixées par le contrat : voir web/README.md (« Hypothèses de format M3 »).
 */
export const AUDIT_WORKFLOW = {
  initial: 'PLANIFIE',
  states: [
    { code: 'PLANIFIE', label: 'Planifié', final: false },
    { code: 'EN_COURS', label: 'En cours', final: false },
    { code: 'RAPPORT', label: 'Rapport', final: false },
    { code: 'CLOTURE', label: 'Clôturé', final: true },
  ],
  transitions: [
    { code: 'demarrer', label: "Démarrer l'audit", from: 'PLANIFIE', to: 'EN_COURS', roles: ['QUALITE'], requires: ['grille'] },
    { code: 'rediger_rapport', label: 'Rédiger le rapport', from: 'EN_COURS', to: 'RAPPORT', roles: ['QUALITE'], requires: ['reponses'] },
    { code: 'cloturer', label: "Clôturer l'audit", from: 'RAPPORT', to: 'CLOTURE', roles: ['QUALITE'], requires: [] },
  ],
};
export const DOCUMENT_WORKFLOW = {
  initial: 'REDACTION',
  states: [
    { code: 'REDACTION', label: 'Rédaction', final: false },
    { code: 'VERIFICATION', label: 'Vérification', final: false },
    { code: 'APPROBATION', label: 'Approbation', final: false },
    { code: 'DIFFUSE', label: 'Diffusé', final: false },
    { code: 'PERIME', label: 'Périmé', final: true },
  ],
  // Le workflow documentaire porte sur les versions : POST /api/documents/{id}/versions/{n}/transitions/{code}.
  transitions: [] as typeof AUDIT_WORKFLOW.transitions,
};
const VERSION_TR: Record<string, { from: string; to: string }> = {
  soumettre: { from: 'REDACTION', to: 'VERIFICATION' }, verifier: { from: 'VERIFICATION', to: 'APPROBATION' },
  refuser_verification: { from: 'VERIFICATION', to: 'REDACTION' }, approuver: { from: 'APPROBATION', to: 'DIFFUSE' },
  refuser_approbation: { from: 'APPROBATION', to: 'REDACTION' }, archiver: { from: 'DIFFUSE', to: 'PERIME' },
};
WORKFLOWS['AUDIT'] = AUDIT_WORKFLOW;
WORKFLOWS['DOCUMENT'] = DOCUMENT_WORKFLOW;
PREFIX['AUDIT'] = 'AUD';
PREFIX['DOCUMENT'] = 'DOC';
Object.assign(LABELS, {
  PLANIFIE: 'Planifié', EN_COURS: 'En cours', RAPPORT: 'Rapport', CLOTURE: 'Clôturé',
  REDACTION: 'Rédaction', APPROBATION: 'Approbation', DIFFUSE: 'Diffusé', PERIME: 'Périmé',
});

type Req = { id: string; standardId: string; code: string; title: string; text: string; parentId: string | null; order: number; auditable: boolean };
type Question = {
  id: string; auditId: string; requirementId: string; requirementCode: string; requirementTitle: string; question: string; order: number;
  result: string | null; comment: string; evidence: string; ncId: string | null; ncReference: string | null; closed: boolean;
};
type Version = { id: string; docId: string; number: string; status: string; note: string; fileName: string; contentType: string; size: number;
  createdAt: string; createdByName: string; current: boolean; body: Buffer; acks: { userId: string; displayName: string; at: string }[] };

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n'
  + '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');
/** Destinataires simulés (rôle OPERATEUR). Salma Bennani est l'utilisateur connecté (username sbennani). */
const RECIPIENTS = [{ userId: 'sbennani', displayName: 'Salma Bennani' }, { userId: 'u1', displayName: 'Karim Tazi' }, { userId: 'u3', displayName: 'Omar Fassi' }];
const GAPS = ['ecart_mineur', 'ecart_majeur'];

export class MockApiM3 extends MockApi {
  standards: { id: string; code: string; title: string; version: string }[] = [];
  requirements: Req[] = [];
  questions: Question[] = [];
  versions: Version[] = [];
  links = new Map<string, string[]>();
  imports: { dryRun: boolean; fileName: string }[] = [];
  lastImportFields: string[] = [];

  constructor() {
    super();
    // ISO 9001:2015 déjà en base (extrait, résumés rédigés par nous).
    this.importRows('ISO9001', 'ISO 9001', '2015', [
      ['8', 'Réalisation des activités opérationnelles', 'Planifier et maîtriser les processus de production.', '', 'non'],
      ['8.5', 'Production et prestation de service', 'Maîtriser les conditions de production.', '8', 'oui'],
      ['8.7', 'Maîtrise des éléments de sortie non conformes', 'Identifier et traiter les produits non conformes.', '8', 'oui'],
    ]);
    const audit = this.create({ type: 'AUDIT', siteId: 's1', title: 'Audit interne ISO 45001 — Entrepôt Nord', description: 'Quais et préparation',
      data: { plannedDate: '2026-11-21', perimetre: 'Quais et préparation', auditeurs: ['Salma Bennani'], audites: ['Karim Tazi'], standardIds: [] } }, undefined, 'u2');
    void audit;
    this.create({ type: 'AUDIT', siteId: 's2', title: 'Audit fournisseur transport', description: '',
      data: { plannedDate: '2026-03-12', perimetre: 'Sous-traitance', auditeurs: ['Karim Tazi'], audites: [], standardIds: ['std-1'] } }, 'CLOTURE', 'u1');
    this.create({ type: 'DOCUMENT', siteId: 's1', title: 'Consignation des énergies', description: '',
      data: { code: 'PR-QHSE-07', docType: 'procedure', processus: 'Maintenance', reviewDate: '2027-11-30',
        destinataires: { roles: ['OPERATEUR'], siteIds: ['s1'] }, verifierRoles: ['QUALITE'], approverRoles: ['DIRECTION'] } }, undefined, 'u2');
  }

  /** Import de lignes code;titre;texte;parent;auditable. Renvoie le rapport du contrat. */
  importRows(code: string, title: string, version: string, rows: string[][], dryRun = false) {
    let std = this.standards.find((s) => s.code === code);
    const errors: { line: number; message: string }[] = [];
    let created = 0;
    let updated = 0;
    const stdId = std?.id ?? `std-${this.standards.length + 1}`;
    const codes = new Set([...this.requirements.filter((r) => r.standardId === stdId).map((r) => r.code)]);
    const pending: Req[] = [];
    rows.forEach((cols, i) => {
      const line = i + 2;
      const [c, t, txt, parent, aud] = cols.map((x) => (x ?? '').trim());
      if (!c || !t) return errors.push({ line, message: 'Code et titre obligatoires.' });
      if (parent && !codes.has(parent)) return errors.push({ line, message: `Parent « ${parent} » inconnu.` });
      if (!['oui', 'non', 'true', 'false', ''].includes(aud.toLowerCase())) return errors.push({ line, message: `Valeur « auditable » invalide : ${aud}.` });
      const existing = this.requirements.find((r) => r.standardId === stdId && r.code === c);
      if (existing) updated++;
      else created++;
      codes.add(c);
      pending.push({ id: existing?.id ?? '', standardId: stdId, code: c, title: t, text: txt, parentId: parent || null, order: i,
        auditable: ['oui', 'true'].includes(aud.toLowerCase()) });
    });
    if (!dryRun && !errors.length) {
      if (!std) this.standards.push((std = { id: stdId, code, title, version }));
      for (const p of pending) {
        const parentId = p.parentId ? this.requirements.find((r) => r.standardId === stdId && r.code === p.parentId)!.id : null;
        const ex = this.requirements.find((r) => r.id === p.id);
        if (ex) Object.assign(ex, { ...p, id: ex.id, parentId });
        else this.requirements.push({ ...p, id: `req-${this.requirements.length + 1}`, parentId });
      }
    }
    return { standardId: std?.id ?? null, created, updated, errors, applied: !dryRun && !errors.length, dryRun };
  }

  protected override missing(r: Rec, requires: string[]): string[] {
    const own = requires.filter((f) => {
      if (f === 'grille') return !this.questions.some((q) => q.auditId === r.id);
      if (f === 'reponses') return this.questions.some((q) => q.auditId === r.id && !q.result);
      return false;
    });
    return [...own, ...super.missing(r, requires.filter((f) => !['grille', 'reponses'].includes(f)))];
  }

  override full(r: Rec) {
    const ids = this.links.get(r.id) ?? [];
    return {
      ...super.full(r),
      requirements: ids.map((id) => this.requirements.find((q) => q.id === id)!).map((q) => ({
        id: q.id, code: q.code, title: q.title, standardCode: this.standards.find((s) => s.id === q.standardId)?.code })),
    };
  }

  private createNc(q: Question, audit: Rec): void {
    const nc = this.create({ type: 'NC', siteId: audit.siteId, title: `Écart audit ${audit.reference} §${q.requirementCode}`,
      description: q.comment, severity: q.result === 'ecart_majeur' ? 'majeure' : 'mineure',
      data: { auditId: audit.id, requirementId: q.requirementId } });
    this.links.set(nc.id, [q.requirementId]);
    q.ncId = nc.id;
    q.ncReference = nc.reference;
  }

  private qOut(q: Question) {
    const { auditId: _a, ...o } = q;
    return o;
  }

  private vOut(v: Version) {
    const { body: _b, acks: _a, docId: _d, ...o } = v;
    return { ...o, statusLabel: LABELS[v.status] };
  }

  /** Effets de bord des transitions AUDIT / DOCUMENT avant leur application par l'API M1. */
  private beforeTransition(r: Rec, code: string): void {
    const t = this.transitionsOf(r).find((x) => x.code === code);
    if (!t || this.missing(r, t.requires ?? []).length) return;
    if (r.type === 'AUDIT' && t.to === 'RAPPORT') {
      this.questions.filter((q) => q.auditId === r.id && GAPS.includes(q.result ?? '') && !q.ncId).forEach((q) => this.createNc(q, r));
    }
  }

  protected override async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    const body = () => JSON.parse(req.postData() ?? '{}');
    let mm: RegExpMatchArray | null;

    if ((mm = p.match(/^\/api\/record-types\/(AUDIT|DOCUMENT)\/workflow$/))) return json(WORKFLOWS[mm[1]]);
    if (p === '/api/standards') return json(this.standards);
    if (p === '/api/standards/import/template') {
      return route.fulfill({ status: 200, headers: { 'content-type': 'text/csv', 'content-disposition': 'attachment; filename="modele-referentiel.csv"' },
        body: 'code;titre;texte;parent;auditable\n' });
    }
    if (p === '/api/standards/import' && m === 'POST') {
      const parts = parseMultipart(req.postDataBuffer(), req.headers()['content-type']);
      const file = parts.find((x) => x.name === 'file');
      if (!file || !/\.(csv|xlsx)$/i.test(file.fileName ?? '')) return json({ error: 'fichier_invalide', message: 'Fichier xlsx ou csv attendu.' }, 400);
      const field = (n: string) => parts.find((x) => x.name === n)?.body.toString('utf8') ?? '';
      this.lastImportFields = parts.filter((x) => !x.fileName).map((x) => x.name);
      const dryRun = url.searchParams.get('dryRun') === 'true';
      this.imports.push({ dryRun, fileName: file.fileName! });
      const rows = file.body.toString('utf8').split(/\r?\n/).slice(1).filter((l) => l.trim()).map((l) => l.split(';'));
      return json(this.importRows(field('standardCode'), field('standardTitle'), field('standardVersion'), rows, dryRun));
    }
    if ((mm = p.match(/^\/api\/standards\/([^/]+)\/requirements$/))) {
      const all = this.requirements.filter((r) => r.standardId === mm![1]);
      const tree = (parent: string | null): unknown[] => all.filter((r) => r.parentId === parent).sort((a, b) => a.order - b.order)
        .map(({ standardId: _s, ...r }) => ({ ...r, children: tree(r.id) }));
      return json(tree(null));
    }
    if ((mm = p.match(/^\/api\/requirements\/([^/]+)$/))) {
      const r = this.requirements.find((x) => x.id === mm![1]);
      if (!r) return json({ error: 'introuvable', message: 'Exigence introuvable.' }, 404);
      const linked = (type: string) => this.records.filter((x) => x.type === type && (this.links.get(x.id) ?? []).includes(r.id))
        .map((x) => ({ id: x.id, reference: x.reference, title: x.title, type: x.type, statusLabel: LABELS[x.status] }));
      return json({ ...r, standardCode: this.standards.find((s) => s.id === r.standardId)?.code, documents: linked('DOCUMENT'), records: [...linked('NC'), ...linked('INCIDENT')],
        findings: this.questions.filter((q) => q.requirementId === r.id && q.result && q.result !== 'conforme').map((q) => ({
          auditId: q.auditId, auditReference: this.records.find((x) => x.id === q.auditId)?.reference, result: q.result, comment: q.comment })) });
    }
    if ((mm = p.match(/^\/api\/records\/([^/]+)\/requirements$/)) && m === 'PUT') {
      this.links.set(mm[1], body());
      return route.fulfill({ status: 204 });
    }
    if (p === '/api/audits') {
      const y = url.searchParams.get('year') ?? '';
      const st = (url.searchParams.get('status') ?? '').split(',').filter(Boolean);
      const mine = url.searchParams.get('auditor') === 'me';
      return json(this.records.filter((r) => r.type === 'AUDIT' && String(r.data['plannedDate'] ?? '').startsWith(y)
        && (!st.length || st.includes(r.status)) && (!mine || (r.data['auditeurs'] as string[]).includes('Salma Bennani')))
        .map((r) => ({ ...this.summary(r), plannedDate: r.data['plannedDate'] })));
    }
    if ((mm = p.match(/^\/api\/audits\/([^/]+)$/)) && m === 'GET') {
      const audit = this.records.find((x) => x.id === mm![1] && x.type === 'AUDIT');
      if (!audit) return json({ error: 'introuvable', message: 'Audit introuvable.' }, 404);
      return json({ ...this.full(audit), questions: this.questions.filter((q) => q.auditId === audit.id).map((q) => this.qOut(q)) });
    }
    if ((mm = p.match(/^\/api\/audits\/([^/]+)\/(checklist\/generate|questions\/[^/]+|answers\/[^/]+|report\.pdf)$/))) {
      const audit = this.records.find((x) => x.id === mm![1] && x.type === 'AUDIT');
      if (!audit) return json({ error: 'introuvable', message: 'Audit introuvable.' }, 404);
      const rest = mm[2];
      if (rest === 'checklist/generate') {
        const b = body() as { standardIds: string[] };
        this.questions = this.questions.filter((q) => q.auditId !== audit.id);
        this.requirements.filter((r) => b.standardIds.includes(r.standardId) && r.auditable).forEach((r, i) => this.questions.push({
          id: `q-${audit.id}-${i + 1}`, auditId: audit.id, requirementId: r.id, requirementCode: r.code, requirementTitle: r.title,
          question: `Les dispositions « ${r.title} » sont-elles appliquées et maîtrisées ?`, order: i, result: null, comment: '', evidence: '',
          ncId: null, ncReference: null, closed: false }));
        audit.data = { ...audit.data, standardIds: b.standardIds };
        return json(this.questions.filter((q) => q.auditId === audit.id).map((q) => this.qOut(q)));
      }
      if (rest === 'report.pdf') {
        if (!['RAPPORT', 'CLOTURE'].includes(audit.status)) return json({ error: 'pas_de_rapport', message: 'Rapport disponible à partir de RAPPORT.' }, 409);
        return route.fulfill({ status: 200, body: PDF, headers: { 'content-type': 'application/pdf', 'content-disposition': `attachment; filename="${audit.reference}.pdf"` } });
      }
      const q = this.questions.find((x) => x.auditId === audit.id && x.id === rest.split('/')[1]);
      if (!q) return json({ error: 'introuvable', message: 'Question introuvable.' }, 404);
      if (rest.startsWith('questions/') && m === 'PATCH') { q.question = body().question; return json(this.qOut(q)); }
      if (rest.startsWith('questions/') && m === 'DELETE') { this.questions = this.questions.filter((x) => x !== q); return route.fulfill({ status: 204 }); }
      if (rest.startsWith('answers/') && m === 'PUT') {
        if (audit.status !== 'EN_COURS') return json({ error: 'audit_non_en_cours', message: "L'audit n'est pas en cours." }, 409);
        Object.assign(q, body());
        if (q.closed && GAPS.includes(q.result ?? '') && !q.ncId) this.createNc(q, audit);
        return json(this.qOut(q));
      }
    }
    if (p === '/api/documents') {
      const st = url.searchParams.get('status'); const ty = url.searchParams.get('type'); const q = (url.searchParams.get('q') ?? '').toLowerCase();
      return json(this.records.filter((r) => r.type === 'DOCUMENT' && (!st || r.status === st) && (!ty || r.data['docType'] === ty)
        && (!q || `${r.data['code']} ${r.title}`.toLowerCase().includes(q))).map((r) => {
          const cur = this.versions.find((v) => v.docId === r.id && v.current);
          return { ...this.summary(r), data: r.data, currentVersion: cur ? { id: cur.id, number: cur.number, fileName: cur.fileName } : null };
        }));
    }
    if (p === '/api/documents' && m === 'POST') return json(this.full(this.create({ ...body(), type: 'DOCUMENT' }, undefined, 'u2')), 201);
    if ((mm = p.match(/^\/api\/documents\/([^/]+)\/versions(?:\/([^/]+)(\/file|\/ack|\/acks|\/transitions\/[^/]+))?$/))) {
      const doc = this.records.find((x) => x.id === mm![1] && x.type === 'DOCUMENT');
      if (!doc) return json({ error: 'introuvable', message: 'Document introuvable.' }, 404);
      const list = this.versions.filter((v) => v.docId === doc.id);
      if (!mm[2] && m === 'GET') return json([...list].reverse().map((v) => this.vOut(v)));
      if (!mm[2] && m === 'POST') {
        if (list.some((v) => ['REDACTION', 'VERIFICATION', 'APPROBATION'].includes(v.status))) {
          return json({ error: 'version_en_cours', message: 'Une version est déjà en circuit de validation.' }, 409);
        }
        const parts = parseMultipart(req.postDataBuffer(), req.headers()['content-type']);
        const file = parts.find((x) => x.name === 'file');
        if (!file) return json({ error: 'fichier_manquant', message: 'Champ « file » manquant.' }, 400);
        const v: Version = { id: `ver-${this.versions.length + 1}`, docId: doc.id, number: String(list.length + 1), status: 'REDACTION', fileName: file.fileName ?? 'fichier',
          contentType: file.contentType ?? 'application/octet-stream', size: file.body.length, createdAt: this.now(), createdByName: 'Salma Bennani',
          note: parts.find((x) => x.name === 'note')?.body.toString('utf8') ?? '', current: false, body: file.body, acks: [] };
        this.versions.push(v);
        doc.status = 'REDACTION';
        return json(this.vOut(v), 201);
      }
      const v = list.find((x) => x.number === mm![2]);
      if (!v) return json({ error: 'introuvable', message: 'Version introuvable.' }, 404);
      if (mm[3]?.startsWith('/transitions/') && m === 'POST') {
        const code = mm[3].slice('/transitions/'.length);
        const t = VERSION_TR[code];
        if (!t || t.from !== v.status) return json({ error: 'transition_impossible', message: `Transition « ${code} » impossible depuis ${v.status}.` }, 409);
        if (t.to === 'DIFFUSE') list.filter((x) => x.current).forEach((x) => { x.current = false; x.status = 'PERIME'; });
        v.status = t.to;
        v.current = t.to === 'DIFFUSE' || (v.current && t.to !== 'PERIME');
        doc.status = t.to;
        return json(this.vOut(v));
      }
      if (mm[3] === '/file') return route.fulfill({ status: 200, body: v.body, headers: { 'content-type': v.contentType, 'content-disposition': `inline; filename="${v.fileName}"`, 'x-content-type-options': 'nosniff' } });
      if (mm[3] === '/ack' && m === 'POST') {
        if (v.status !== 'DIFFUSE') return json({ error: 'non_diffuse', message: "Version non diffusée." }, 409);
        if (!v.acks.some((a) => a.userId === 'sbennani')) v.acks.push({ userId: 'sbennani', displayName: 'Salma Bennani', at: this.now() });
        return json(null, 201);
      }
      if (mm[3] === '/acks') {
        const read = v.acks;
        return json({ read, pending: RECIPIENTS.filter((u) => !read.some((a) => a.userId === u.userId)) });
      }
    }
    const tm = p.match(/^\/api\/records\/([^/]+)\/transitions\/(.+)$/);
    if (tm && m === 'POST') {
      const r = this.records.find((x) => x.id === tm[1]);
      if (r) this.beforeTransition(r, tm[2]);
    }
    return super.handle(route);
  }
}
void USERS;

/** Lecture des parties d'un corps multipart (champs et fichiers). */
export function parseMultipart(buf: Buffer | null, ct: string | undefined): { name: string; fileName?: string; contentType?: string; body: Buffer }[] {
  const boundary = ct?.match(/boundary=(.+)$/)?.[1];
  if (!buf || !boundary) return [];
  const sep = Buffer.from(`--${boundary}`);
  const out: { name: string; fileName?: string; contentType?: string; body: Buffer }[] = [];
  let start = buf.indexOf(sep);
  while (start >= 0) {
    const next = buf.indexOf(sep, start + sep.length);
    if (next < 0) break;
    const part = buf.subarray(start + sep.length + 2, next - 2);
    const headEnd = part.indexOf('\r\n\r\n');
    const head = part.subarray(0, headEnd).toString('utf8');
    const name = head.match(/name="([^"]*)"/)?.[1];
    if (name) out.push({ name, fileName: head.match(/filename="([^"]*)"/)?.[1], contentType: head.match(/Content-Type:\s*(\S+)/i)?.[1],
      body: Buffer.from(part.subarray(headEnd + 4)) });
    start = next;
  }
  return out;
}
