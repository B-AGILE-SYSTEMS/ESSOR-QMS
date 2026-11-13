import { Page, Route } from '@playwright/test';

/**
 * API simulée, conforme aux contrats M1 et M2 (docs/pilotage/cartes/M1-contrat.md, M2-contrat.md), en mémoire.
 * Elle reproduit les règles du workflow NC (champs requis, actions toutes FAITE) pour tester le parcours.
 */
export type Rec = {
  id: string; reference: string; type: string; status: string; title: string; description: string | null;
  severity: string | null; siteId: string; siteName: string; ownerId: string | null; ownerName: string | null;
  dueDate: string | null; data: Record<string, unknown>; createdAt: string; createdBy: string; createdByName: string; parentId?: string;
  actionType?: string; clientId?: string | null; occurredAt?: string | null;
};
export type Att = { id: string; recordId: string; fileName: string; contentType: string; size: number; createdAt: string; createdByName: string; body: Buffer };

export const SITES = [{ id: 's1', code: 'KEN', name: 'Kénitra' }, { id: 's2', code: 'CAS', name: 'Casablanca' }];
export const USERS = [{ id: 'u1', displayName: 'Karim Tazi' }, { id: 'u2', displayName: 'Salma Bennani' }];

export const NC_WORKFLOW = {
  initial: 'DECLAREE',
  states: [
    { code: 'DECLAREE', label: 'Déclarée', final: false },
    { code: 'QUALIFIEE', label: 'Qualifiée', final: false },
    { code: 'ANALYSE', label: 'Analyse', final: false },
    { code: 'ACTIONS', label: 'Actions', final: false },
    { code: 'VERIFICATION', label: 'Vérification', final: false },
    { code: 'REJETEE', label: 'Rejetée', final: true },
    { code: 'CLOTUREE', label: 'Clôturée', final: true },
  ],
  transitions: [
    { code: 'qualifier', label: 'Qualifier', from: 'DECLAREE', to: 'QUALIFIEE', roles: ['QUALITE'], requires: ['severity', 'ownerId'] },
    { code: 'rejeter', label: 'Rejeter', from: 'DECLAREE', to: 'REJETEE', roles: ['QUALITE'], requires: [] },
    { code: 'lancer_analyse', label: "Lancer l'analyse", from: 'QUALIFIEE', to: 'ANALYSE', roles: ['QUALITE'], requires: [] },
    { code: 'valider_analyse', label: "Valider l'analyse", from: 'ANALYSE', to: 'ACTIONS', roles: ['QUALITE'], requires: ['data.causeRacine'] },
    { code: 'renvoyer_analyse', label: "Renvoyer en analyse", from: 'ACTIONS', to: 'ANALYSE', roles: ['QUALITE'], requires: [] },
    { code: 'lancer_verification', label: 'Lancer la vérification', from: 'ACTIONS', to: 'VERIFICATION', roles: ['QUALITE'], requires: ['actions'] },
    { code: 'cloturer', label: 'Clôturer', from: 'VERIFICATION', to: 'CLOTUREE', roles: ['QUALITE'], requires: [] },
    { code: 'rouvrir_actions', label: 'Non efficace — rouvrir', from: 'VERIFICATION', to: 'ACTIONS', roles: ['QUALITE'], requires: [] },
  ],
};
export const INCIDENT_WORKFLOW = {
  initial: 'DECLARE',
  states: [
    { code: 'DECLARE', label: 'Déclaré', final: false },
    { code: 'EN_ANALYSE', label: 'En analyse', final: false },
    { code: 'ACTIONS', label: 'Actions', final: false },
    { code: 'CLOS', label: 'Clos', final: true },
    { code: 'CLASSE_SANS_SUITE', label: 'Classé sans suite', final: true },
  ],
  transitions: [
    { code: 'analyser', label: 'Analyser', from: 'DECLARE', to: 'EN_ANALYSE', roles: ['QUALITE'], requires: [] },
    { code: 'classer', label: 'Classer sans suite', from: 'DECLARE', to: 'CLASSE_SANS_SUITE', roles: ['QUALITE'], requires: [] },
    { code: 'planifier', label: 'Planifier les actions', from: 'EN_ANALYSE', to: 'ACTIONS', roles: ['QUALITE'], requires: [] },
    { code: 'clore', label: 'Clore', from: 'ACTIONS', to: 'CLOS', roles: ['QUALITE'], requires: ['actions'] },
  ],
};
export const WORKFLOWS: Record<string, typeof NC_WORKFLOW> = { NC: NC_WORKFLOW, INCIDENT: INCIDENT_WORKFLOW };
export const PREFIX: Record<string, string> = { NC: 'NC', INCIDENT: 'INC', ACTION: 'ACT' };
/** Formats admis (contrat M2) et signatures du contenu réel. */
const MAGIC: Record<string, number[]> = { 'image/jpeg': [0xff, 0xd8, 0xff], 'image/png': [0x89, 0x50, 0x4e, 0x47], 'application/pdf': [0x25, 0x50, 0x44, 0x46] };
const MAX_SIZE = 10 * 1024 * 1024;

const ACTION_TR = [
  { code: 'demarrer', label: 'Démarrer', from: 'A_FAIRE', to: 'EN_COURS' },
  { code: 'terminer', label: 'Marquer faite', from: 'EN_COURS', to: 'FAITE' },
];
export const LABELS: Record<string, string> = {
  DECLAREE: 'Déclarée', QUALIFIEE: 'Qualifiée', ANALYSE: 'Analyse', ACTIONS: 'Actions', VERIFICATION: 'Vérification',
  CLOTUREE: 'Clôturée', REJETEE: 'Rejetée', DECLARE: 'Déclaré', EN_ANALYSE: 'En analyse', CLOS: 'Clos', CLASSE_SANS_SUITE: 'Classé sans suite', A_FAIRE: 'À faire', EN_COURS: 'En cours', FAITE: 'Faite',
};

export class MockApi {
  records: Rec[] = [];
  attachments: Att[] = [];
  /** Réponses mémorisées par Idempotency-Key (contrat M2). */
  private idem = new Map<string, { status: number; body: unknown }>();
  notifications: { id: string; type: string; title: string; recordId: string; read: boolean; createdAt: string }[] = [];
  /** Notifications pas encore poussées sur le flux SSE. */
  private stream: string[] = [];
  private seq = 0;
  rules = [
    { event: 'RECORD_ASSIGNED', channel: 'IN_APP', enabled: true }, { event: 'RECORD_ASSIGNED', channel: 'EMAIL', enabled: false },
    { event: 'TRANSITION_DONE', channel: 'IN_APP', enabled: true }, { event: 'TRANSITION_DONE', channel: 'EMAIL', enabled: true },
    { event: 'ACTION_OVERDUE', channel: 'IN_APP', enabled: true }, { event: 'ACTION_OVERDUE', channel: 'EMAIL', enabled: true },
  ];

  constructor() {
    this.create({ type: 'NC', siteId: 's2', title: 'Fuite hydraulique chariot CH-07', description: '', severity: 'majeure', data: {} }, 'ANALYSE', 'u1');
    this.create({ type: 'NC', siteId: 's1', title: 'Garde-corps quai 4 déformé', description: '', severity: 'critique', data: {} }, 'ACTIONS', 'u1');
    // Presqu'accident déclaré hors ligne depuis le mobile (clientId + occurredAt).
    this.create({
      type: 'INCIDENT', siteId: 's1', title: 'Chute de palette évitée allée B', description: 'Palette mal filmée, basculée au gerbage.',
      severity: 'majeure', clientId: '5d3c1f0e-8a2b-4c6d-9e7f-0a1b2c3d4e5f', occurredAt: '2026-11-11T22:15:00.000Z',
      data: { categorie: 'presqu_accident', lieu: 'Kénitra — allée B', blesses: false, temoins: 'Omar Fassi' },
    }, undefined, 'u1');
  }

  protected now(): string {
    return new Date(Date.UTC(2026, 10, 12, 8, this.seq++)).toISOString();
  }

  protected create(b: Record<string, unknown>, status?: string, ownerId: string | null = null, parentId?: string): Rec {
    const type = String(b['type']);
    const n = this.records.filter((r) => r.type === type).length + 1;
    const owner = USERS.find((u) => u.id === ownerId);
    const site = SITES.find((s) => s.id === b['siteId']) ?? SITES[0];
    const rec: Rec = {
      id: `${type.toLowerCase()}-${n}`, reference: `${PREFIX[type] ?? type}-2026-${String(n).padStart(4, '0')}`, type,
      status: status ?? WORKFLOWS[type]?.initial ?? 'A_FAIRE', title: String(b['title']), description: (b['description'] as string) ?? null,
      severity: (b['severity'] as string) ?? null, siteId: site.id, siteName: site.name, ownerId: owner?.id ?? null,
      ownerName: owner?.displayName ?? null, dueDate: (b['dueDate'] as string) ?? null, data: (b['data'] as Record<string, unknown>) ?? {},
      createdAt: this.now(), createdBy: 'u-youssef', createdByName: 'Youssef Amrani', parentId, actionType: b['actionType'] as string | undefined,
      clientId: (b['clientId'] as string) ?? null, occurredAt: (b['occurredAt'] as string) ?? null,
    };
    this.records.push(rec);
    return rec;
  }

  protected notify(title: string, recordId: string): void {
    const n = { id: `n${this.notifications.length + 1}`, type: 'TRANSITION_DONE', title, recordId, read: false, createdAt: this.now() };
    this.notifications.push(n);
    this.stream.push(JSON.stringify(n));
  }

  private children(r: Rec): Rec[] {
    return this.records.filter((c) => c.parentId === r.id);
  }

  protected missing(r: Rec, requires: string[]): string[] {
    return requires.filter((f) => {
      if (f === 'actions') return !this.children(r).length || this.children(r).some((c) => c.status !== 'FAITE');
      if (f.startsWith('data.')) return !r.data[f.slice(5)];
      return !(r as unknown as Record<string, unknown>)[f];
    });
  }

  protected transitionsOf(r: Rec) {
    const list = WORKFLOWS[r.type] ? WORKFLOWS[r.type].transitions : ACTION_TR.map((t) => ({ ...t, roles: [], requires: [] as string[] }));
    return list.filter((t) => t.from === r.status);
  }

  summary(r: Rec) {
    const { data, description, createdBy, createdByName, parentId, ...s } = r;
    void data; void description; void createdBy; void createdByName; void parentId;
    return { ...s, statusLabel: LABELS[r.status] };
  }

  full(r: Rec) {
    return {
      ...r, statusLabel: LABELS[r.status],
      availableTransitions: this.transitionsOf(r).map((t) => ({ code: t.code, label: t.label, missing: this.missing(r, t.requires ?? []) })),
      children: this.children(r).map((c) => this.summary(c)),
    };
  }

  async install(page: Page): Promise<void> {
    await page.route('**/api/**', (route) => this.handle(route));
  }

  protected async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    const multipart = (req.headers()['content-type'] ?? '').startsWith('multipart/form-data');
    const body = req.postData() && !multipart ? JSON.parse(req.postData()!) : {};
    const key = req.headers()['idempotency-key'];
    if (key && m === 'POST' && this.idem.has(key)) {
      const prev = this.idem.get(key)!;
      return route.fulfill({ status: prev.status, contentType: 'application/json', body: JSON.stringify(prev.body) });
    }
    const json = (data: unknown, status = 200) => {
      if (key && m === 'POST' && status < 300) this.idem.set(key, { status, body: data });
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    };
    let mm: RegExpMatchArray | null;

    if (p === '/api/me') return json({ username: 'sbennani', displayName: 'Salma Bennani', tenant: { id: 't1', code: 'AL', name: 'Atlas Logistique' }, sites: SITES, roles: ['ADMIN', 'QUALITE'] });
    if (p === '/api/tenant/branding') return json({ logoUrl: null, primaryColor: '#c2410c', theme: 'clair' });
    if (p === '/api/notifications/stream') {
      const events = this.stream.splice(0).map((d) => `event: notification\ndata: ${d}\n\n`).join('');
      return route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' }, body: `retry: 500\n\n${events}` });
    }
    if (p === '/api/notifications') { const items = [...this.notifications].reverse(); return json({ items, total: items.length, unread: items.filter((n) => !n.read).length, page: 0, size: 50 }); }
    if (p === '/api/notifications/read-all') { this.notifications.forEach((n) => (n.read = true)); return json(null); }
    if ((mm = p.match(/^\/api\/notifications\/([^/]+)\/read$/))) { const n = this.notifications.find((x) => x.id === mm![1]); if (n) n.read = true; return json(null); }
    if (p === '/api/notification-rules') { if (m === 'PUT') this.rules = body; return json(this.rules); }
    if (p === '/api/users') return json(USERS.filter((u) => u.displayName.toLowerCase().includes((url.searchParams.get('q') ?? '').toLowerCase())));
    if (p === '/api/record-types') {
      return json([{ code: 'NC', label: 'Non-conformité', prefix: 'NC' }, { code: 'INCIDENT', label: 'Incident', prefix: 'INC' },
        { code: 'ACTION', label: 'Action', prefix: 'ACT' }]);
    }
    if (p === '/api/record-types/INCIDENT/workflow') return json(INCIDENT_WORKFLOW);
    if (p === '/api/sync/changes') {
      const since = url.searchParams.get('since') ?? '';
      return json({ records: this.records.filter((r) => r.createdAt > since).map((r) => this.summary(r)), serverTime: this.now() });
    }
    if ((mm = p.match(/^\/api\/attachments\/([^/]+)$/))) {
      const a = this.attachments.find((x) => x.id === mm![1]);
      if (!a) return json({ error: 'introuvable', message: 'Pièce jointe introuvable.' }, 404);
      return route.fulfill({ status: 200, body: a.body, headers: {
        'content-type': a.contentType, 'content-disposition': `attachment; filename="${a.fileName}"`, 'x-content-type-options': 'nosniff' } });
    }
    if (p === '/api/record-types/NC/workflow') {
      if (m === 'PUT') return body.states?.length ? json(body) : json({ error: 'workflow_invalide', message: 'Le workflow doit déclarer au moins un état.' }, 400);
      return json(NC_WORKFLOW);
    }
    if (p === '/api/dashboard/summary') {
      const open = this.records.filter((r) => r.type === 'NC' && !['CLOTUREE', 'REJETEE'].includes(r.status));
      return json({ ncOpen: open.length, ncToQualify: open.filter((r) => r.status === 'DECLAREE').length, actionsOverdue: 1, ncCostMonth: 48300 });
    }
    if (p === '/api/my-tasks') {
      return json(this.records.filter((r) => !['CLOTUREE', 'REJETEE', 'FAITE'].includes(r.status))
        .map((r, i) => ({ ...this.summary(r), dueDate: `2026-11-${String(10 + i * 3).padStart(2, '0')}`, overdue: i === 0 })));
    }
    if (p === '/api/records' && m === 'GET') {
      const t = url.searchParams.get('type'); const st = url.searchParams.get('status'); const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const items = this.records.filter((r) => r.type === t && (!st || r.status === st) && (!q || r.title.toLowerCase().includes(q)));
      return json({ items: items.map((r) => this.summary(r)), total: items.length, page: 0, size: 20 });
    }
    if (p === '/api/records' && m === 'POST') return json(this.full(this.create(body)), 201);
    if ((mm = p.match(/^\/api\/records\/([^/]+)(\/.*)?$/))) {
      const r = this.records.find((x) => x.id === mm![1]);
      if (!r) return json({ error: 'introuvable', message: 'Fiche introuvable.' }, 404);
      const rest = mm[2] ?? '';
      if (!rest && m === 'GET') return json(this.full(r));
      if (!rest && m === 'PATCH') {
        Object.assign(r, body);
        if (body.ownerId) r.ownerName = USERS.find((u) => u.id === body.ownerId)?.displayName ?? null;
        return json(this.full(r));
      }
      if (rest === '/attachments' && m === 'GET') {
        return json(this.attachments.filter((a) => a.recordId === r.id).map(({ body: _b, recordId: _r, ...a }) => a));
      }
      if (rest === '/attachments' && m === 'POST') {
        const file = parseMultipartFile(req.postDataBuffer(), req.headers()['content-type']);
        if (!file) return json({ error: 'fichier_manquant', message: 'Champ « file » manquant.' }, 400);
        const magic = MAGIC[file.contentType];
        if (!magic || !magic.every((b, i) => file.body[i] === b)) {
          return json({ error: 'type_refuse', message: 'Seuls les fichiers JPEG, PNG et PDF sont acceptés.' }, 415);
        }
        if (file.body.length > MAX_SIZE) return json({ error: 'trop_volumineux', message: 'Fichier de plus de 10 Mo.' }, 413);
        const a: Att = { id: `att-${this.attachments.length + 1}`, recordId: r.id, fileName: file.fileName, contentType: file.contentType,
          size: file.body.length, createdAt: this.now(), createdByName: 'Salma Bennani', body: file.body };
        this.attachments.push(a);
        const { body: _b, recordId: _r, ...out } = a;
        return json(out, 201);
      }
      if (rest === '/history') return json([{ at: r.createdAt, author: r.createdByName, action: 'Création', comment: null }]);
      if (rest === '/actions') return json(this.full(this.create({ ...body, type: 'ACTION', siteId: r.siteId }, 'A_FAIRE', body.ownerId, r.id)), 201);
      const tm = rest.match(/^\/transitions\/(.+)$/);
      if (tm) {
        const t = this.transitionsOf(r).find((x) => x.code === tm[1]);
        const miss = t ? this.missing(r, t.requires ?? []) : [];
        if (!t || miss.length) return json({ error: 'transition_impossible', message: `Transition impossible : il manque ${miss.join(', ')}.`, details: { missing: miss } }, 409);
        r.status = t.to;
        if (WORKFLOWS[r.type]) this.notify(`${r.reference} : ${t.label} (${LABELS[t.to]})`, r.id);
        return json(this.full(r));
      }
    }
    return json({ error: 'non_simule', message: `${m} ${p} non simulé` }, 501);
  }
}

/** Lecture minimale d'un corps multipart (une seule partie `file`). */
export function parseMultipartFile(buf: Buffer | null, ct: string | undefined): { fileName: string; contentType: string; body: Buffer } | null {
  const boundary = ct?.match(/boundary=(.+)$/)?.[1];
  if (!buf || !boundary) return null;
  const sep = Buffer.from(`--${boundary}`);
  let start = buf.indexOf(sep);
  while (start >= 0) {
    const next = buf.indexOf(sep, start + sep.length);
    if (next < 0) break;
    const part = buf.subarray(start + sep.length + 2, next - 2);
    const headEnd = part.indexOf('\r\n\r\n');
    const head = part.subarray(0, headEnd).toString('utf8');
    if (/name="file"/.test(head)) {
      return {
        fileName: head.match(/filename="([^"]*)"/)?.[1] ?? 'fichier',
        contentType: head.match(/Content-Type:\s*(\S+)/i)?.[1] ?? 'application/octet-stream',
        body: Buffer.from(part.subarray(headEnd + 4)),
      };
    }
    start = next;
  }
  return null;
}
