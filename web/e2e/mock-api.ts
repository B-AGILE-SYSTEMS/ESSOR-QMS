import { Page, Route } from '@playwright/test';

/**
 * API simulée, conforme au contrat M1 (docs/pilotage/cartes/M1-contrat.md), en mémoire.
 * Elle reproduit les règles du workflow NC (champs requis, actions toutes FAITE) pour tester le parcours.
 */
type Rec = {
  id: string; reference: string; type: string; status: string; title: string; description: string | null;
  severity: string | null; siteId: string; siteName: string; ownerId: string | null; ownerName: string | null;
  dueDate: string | null; data: Record<string, unknown>; createdAt: string; createdBy: string; createdByName: string; parentId?: string;
  actionType?: string;
};

const SITES = [{ id: 's1', code: 'KEN', name: 'Kénitra' }, { id: 's2', code: 'CAS', name: 'Casablanca' }];
const USERS = [{ id: 'u1', displayName: 'Karim Tazi' }, { id: 'u2', displayName: 'Salma Bennani' }];

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
const ACTION_TR = [
  { code: 'demarrer', label: 'Démarrer', from: 'A_FAIRE', to: 'EN_COURS' },
  { code: 'terminer', label: 'Marquer faite', from: 'EN_COURS', to: 'FAITE' },
];
const LABELS: Record<string, string> = {
  DECLAREE: 'Déclarée', QUALIFIEE: 'Qualifiée', ANALYSE: 'Analyse', ACTIONS: 'Actions', VERIFICATION: 'Vérification',
  CLOTUREE: 'Clôturée', REJETEE: 'Rejetée', A_FAIRE: 'À faire', EN_COURS: 'En cours', FAITE: 'Faite',
};

export class MockApi {
  records: Rec[] = [];
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
  }

  private now(): string {
    return new Date(Date.UTC(2026, 10, 12, 8, this.seq++)).toISOString();
  }

  private create(b: Record<string, unknown>, status?: string, ownerId: string | null = null, parentId?: string): Rec {
    const type = String(b['type']);
    const n = this.records.filter((r) => r.type === type).length + 1;
    const owner = USERS.find((u) => u.id === ownerId);
    const site = SITES.find((s) => s.id === b['siteId']) ?? SITES[0];
    const rec: Rec = {
      id: `${type.toLowerCase()}-${n}`, reference: `${type === 'NC' ? 'NC' : 'ACT'}-2026-${String(n).padStart(4, '0')}`, type,
      status: status ?? (type === 'NC' ? 'DECLAREE' : 'A_FAIRE'), title: String(b['title']), description: (b['description'] as string) ?? null,
      severity: (b['severity'] as string) ?? null, siteId: site.id, siteName: site.name, ownerId: owner?.id ?? null,
      ownerName: owner?.displayName ?? null, dueDate: (b['dueDate'] as string) ?? null, data: (b['data'] as Record<string, unknown>) ?? {},
      createdAt: this.now(), createdBy: 'u-youssef', createdByName: 'Youssef Amrani', parentId, actionType: b['actionType'] as string | undefined,
    };
    this.records.push(rec);
    return rec;
  }

  private notify(title: string, recordId: string): void {
    const n = { id: `n${this.notifications.length + 1}`, type: 'TRANSITION_DONE', title, recordId, read: false, createdAt: this.now() };
    this.notifications.push(n);
    this.stream.push(JSON.stringify(n));
  }

  private children(r: Rec): Rec[] {
    return this.records.filter((c) => c.parentId === r.id);
  }

  private missing(r: Rec, requires: string[]): string[] {
    return requires.filter((f) => {
      if (f === 'actions') return !this.children(r).length || this.children(r).some((c) => c.status !== 'FAITE');
      if (f.startsWith('data.')) return !r.data[f.slice(5)];
      return !(r as unknown as Record<string, unknown>)[f];
    });
  }

  private transitionsOf(r: Rec) {
    const list = r.type === 'NC' ? NC_WORKFLOW.transitions : ACTION_TR.map((t) => ({ ...t, roles: [], requires: [] as string[] }));
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

  private async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    const body = req.postData() ? JSON.parse(req.postData()!) : {};
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
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
    if (p === '/api/record-types') return json([{ code: 'NC', label: 'Non-conformité', prefix: 'NC' }, { code: 'ACTION', label: 'Action', prefix: 'ACT' }]);
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
      if (rest === '/history') return json([{ at: r.createdAt, author: r.createdByName, action: 'Création', comment: null }]);
      if (rest === '/actions') return json(this.full(this.create({ ...body, type: 'ACTION', siteId: r.siteId }, 'A_FAIRE', body.ownerId, r.id)), 201);
      const tm = rest.match(/^\/transitions\/(.+)$/);
      if (tm) {
        const t = this.transitionsOf(r).find((x) => x.code === tm[1]);
        const miss = t ? this.missing(r, t.requires ?? []) : [];
        if (!t || miss.length) return json({ error: 'transition_impossible', message: `Transition impossible : il manque ${miss.join(', ')}.`, details: { missing: miss } }, 409);
        r.status = t.to;
        if (r.type === 'NC') this.notify(`${r.reference} : ${t.label} (${LABELS[t.to]})`, r.id);
        return json(this.full(r));
      }
    }
    return json({ error: 'non_simule', message: `${m} ${p} non simulé` }, 501);
  }
}
