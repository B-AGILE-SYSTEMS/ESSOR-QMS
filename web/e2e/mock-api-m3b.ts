import { Route } from '@playwright/test';
import { MockApiM3 } from './mock-api-m3';

/**
 * API simulée M3b (docs/pilotage/cartes/M3B-contrat.md) : données de base, lots, généalogie, rappel, plans de contrôle,
 * contrôles et carte de contrôle. Étend l'API M1→M3. Démo : lot fournisseur → OF → lot fini expédié à 2 clients.
 */
type Lot = {
  id: string; numero: string; articleId: string; fournisseurId: string | null; lotFournisseur: string | null; quantite: number; unite: string;
  dlc: string | null; siteId: string | null; qualityStatus: string; historique: { from: string | null; status: string; reason: string | null; byName: string; at: string }[];
};
type Link = { id: string; fromId: string; kind: string; targetLotId?: string; partnerId?: string; quantity: number; reference: string };
type Insp = {
  id: string; lotId: string; controlPlanId: string; planVersion: number; point: string; status: string; sampleSize: number; codeLetter: string;
  ac: number; re: number; results: unknown[]; decision: string | null; createdAt: string; authorName: string;
};

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n'
  + '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

export const ARTICLES = [
  { id: 'a1', code: 'MP-TUBE-12', designation: 'Tube acier Ø 12', famille: 'Matière', unite: 'm', suivi: 'lot', dlc: false },
  { id: 'a2', code: 'PF-AXE-12', designation: 'Axe usiné Ø 12,00', famille: 'Produit fini', unite: 'pcs', suivi: 'lot', dlc: false },
];
export const PARTNERS = [
  { id: 'p1', code: 'F-ACIERNORD', nom: 'Aciers du Nord', type: 'fournisseur' },
  { id: 'p2', code: 'C-RENO', nom: 'Renault Tanger', type: 'client' },
  { id: 'p3', code: 'C-STELL', nom: 'Stellantis Kénitra', type: 'client' },
];
const DIAM = { code: 'DIAM', libelle: 'Diamètre extérieur', type: 'mesure', nominal: 12, tolMin: 11.97, tolMax: 12.03, unite: 'mm',
  instrumentRequis: true, criticite: 'majeure' };
const CERT = { code: 'CERT', libelle: 'Certificat matière 3.1', type: 'document', nominal: null, tolMin: null, tolMax: null, unite: null,
  instrumentRequis: false, criticite: 'critique' };

export class MockApiM3b extends MockApiM3 {
  lots: Lot[] = [];
  lotLinks: Link[] = [];
  inspections: Insp[] = [];
  plans: Record<string, unknown>[] = [
    { id: 'cp1', code: 'CP-REC-TUBE', libelle: 'Réception tube acier', version: 2, point: 'RECEPTION', target: { type: 'article', value: 'MP-TUBE-12' },
      characteristics: [DIAM, CERT], sampling: { mode: 'iso2859', nqa: 1.0, niveau: 'II' }, frequency: null },
    { id: 'cp2', code: 'CP-ENC-AXE', libelle: 'En-cours usinage axe', version: 1, point: 'PRODUCTION_ENCOURS', target: { type: 'article', value: 'PF-AXE-12' },
      characteristics: [{ ...DIAM, nominal: 12, tolMin: 11.97, tolMax: 12.03 }], sampling: { mode: 'fixe', taille: 5 }, frequency: { pieces: 50 } },
  ];
  webhookEvents: Record<string, unknown>[] = [];
  webhook = { url: 'http://wms.local/hooks/qualite', enabled: true, hasSecret: true };
  lastWebhookPut: Record<string, unknown> | null = null;
  statusCalls: { id: string; status: string; reason: string }[] = [];
  private n = 0;

  constructor() {
    super();
    const raw = this.addLot({ numero: 'R-2611', articleId: 'a1', fournisseurId: 'p1', lotFournisseur: 'AN-77812', quantite: 600, unite: 'm', qualityStatus: 'LIBERE' });
    const fini = this.addLot({ numero: 'PF-2611-B', articleId: 'a2', fournisseurId: null, lotFournisseur: null, quantite: 1240, unite: 'pcs', qualityStatus: 'LIBERE' });
    this.lotLinks.push(
      { id: 'k1', fromId: raw.id, kind: 'consumed_by', targetLotId: fini.id, quantity: 540, reference: 'OF-2611' },
      { id: 'k2', fromId: fini.id, kind: 'shipped_to', partnerId: 'p2', quantity: 600, reference: 'BL-8841 · SSCC 003761234500000017' },
      { id: 'k3', fromId: fini.id, kind: 'shipped_to', partnerId: 'p3', quantity: 400, reference: 'BL-8852' },
    );
  }

  private addLot(l: Partial<Lot> & { numero: string; articleId: string }): Lot {
    const lot: Lot = { id: `lot-${++this.n}`, fournisseurId: null, lotFournisseur: null, quantite: 0, unite: 'pcs', dlc: null, siteId: 's1',
      qualityStatus: 'EN_ATTENTE', historique: [], ...l };
    lot.historique.push({ from: null, status: lot.qualityStatus, reason: 'Création', byName: 'Salma Bennani', at: '2026-11-10T08:00:00Z' });
    this.lots.push(lot);
    return lot;
  }

  private view(l: Lot) {
    const a = ARTICLES.find((x) => x.id === l.articleId)!;
    const f = PARTNERS.find((x) => x.id === l.fournisseurId);
    return { id: l.id, numero: l.numero, article: { id: a.id, code: a.code, designation: a.designation },
      fournisseur: f ? { id: f.id, code: f.code, nom: f.nom } : null, lotFournisseur: l.lotFournisseur, quantite: l.quantite, unite: l.unite,
      dlc: l.dlc, site: { id: 's1', code: 'KEN', name: 'Kénitra' }, qualityStatus: l.qualityStatus, historique: [...l.historique].reverse() };
  }

  setLotStatus(l: Lot, status: string, reason: string): void {
    l.historique.push({ from: l.qualityStatus, status, reason, byName: 'Salma Bennani', at: this.now() });
    l.qualityStatus = status;
    this.webhookEvents.push({ event: 'lot.status_changed', lotId: l.id, numero: l.numero, article: ARTICLES.find((a) => a.id === l.articleId)!.code,
      status, reason, at: this.now() });
  }

  private partner(id?: string) {
    const p = PARTNERS.find((x) => x.id === id)!;
    return { id: p.id, code: p.code, nom: p.nom };
  }

  private trace(id: string, dir: string, depth: number): unknown {
    const lot = this.lots.find((l) => l.id === id)!;
    const children = depth <= 0 ? [] : dir === 'down'
      ? this.lotLinks.filter((k) => k.fromId === id).map((k) => ({
        link: { kind: k.kind, quantity: k.quantity, reference: k.reference, partner: k.partnerId ? this.partner(k.partnerId) : null },
        node: k.targetLotId ? this.trace(k.targetLotId, dir, depth - 1) : null }))
      : this.lotLinks.filter((k) => k.targetLotId === id).map((k) => ({
        link: { kind: k.kind, quantity: k.quantity, reference: k.reference }, node: this.trace(k.fromId, dir, depth - 1) }));
    return { lot: this.view(lot), children };
  }

  private descendants(id: string): Lot[] {
    return this.lotLinks.filter((k) => k.fromId === id && k.targetLotId)
      .flatMap((k) => [this.lots.find((l) => l.id === k.targetLotId)!, ...this.descendants(k.targetLotId!)]);
  }

  private recall(id: string) {
    const src = this.lots.find((l) => l.id === id)!;
    const impacted = this.descendants(id);
    const all = [src, ...impacted];
    const ships = this.lotLinks.filter((k) => k.kind === 'shipped_to' && all.some((l) => l.id === k.fromId));
    const clients = [...new Set(ships.map((k) => k.partnerId!))].map((pid) => ({
      partner: this.partner(pid), quantite: ships.filter((k) => k.partnerId === pid).reduce((s, k) => s + k.quantity, 0),
      references: ships.filter((k) => k.partnerId === pid).map((k) => k.reference) }));
    const expedie = ships.reduce((s, k) => s + k.quantity, 0);
    const shippedFinished = impacted.reduce((s, l) => s + l.quantite, 0);
    return { lotsImpactes: impacted.map((l) => this.view(l)), clients,
      quantites: { enStock: shippedFinished - expedie, enCours: 0, expedie } };
  }

  private spc() {
    const means = [12.004, 11.998, 12.006, 12.001, 11.995, 12.003, 12.009, 12.002, 11.997, 12.021, 12.005, 12.006, 12.007, 12.004, 12.008,
      12.006, 12.009, 11.999, 12.002, 12.0];
    const ranges = [0.012, 0.009, 0.015, 0.011, 0.01, 0.013, 0.014, 0.008, 0.012, 0.016, 0.01, 0.011, 0.009, 0.013, 0.012, 0.031, 0.011, 0.01, 0.012, 0.009];
    return {
      points: means.map((m, i) => ({ index: i + 1, mean: m, range: ranges[i], at: `2026-11-12T${String(6 + Math.floor(i / 2)).padStart(2, '0')}:00:00Z` })),
      limits: { xbar: { lcl: 11.9893, cl: 12.0041, ucl: 12.0189 }, r: { lcl: 0, cl: 0.0129, ucl: 0.0273 } },
      cp: 1.12, cpk: 1.05, tolMin: 11.97, tolMax: 12.03,
      alerts: [{ type: 'hors_limites', index: 10, message: 'Moyenne au-dessus de UCL' },
        { type: 'sept_points', index: 17, message: '7 points consécutifs au-dessus de la ligne centrale' }],
    };
  }

  protected override async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    const body = () => JSON.parse(req.postData() ?? '{}');
    let mm: RegExpMatchArray | null;

    if (p === '/api/master-data/articles') return json(ARTICLES);
    if (p === '/api/master-data/partners') return json(PARTNERS);
    if ((mm = p.match(/^\/api\/master-data\/(articles|partners)\/import$/))) {
      return json({ created: mm[1] === 'articles' ? 2 : 3, updated: 0, errors: [], dryRun: url.searchParams.get('dryRun') === 'true' });
    }
    if (p === '/api/connectors/webhook') {
      if (m === 'PUT') {
        this.lastWebhookPut = body();
        this.webhook = { url: String(this.lastWebhookPut!['url']), enabled: !!this.lastWebhookPut!['enabled'], hasSecret: true };
      }
      return json(this.webhook);
    }
    if (p === '/api/lots' && m === 'POST') {
      const b = body();
      if (this.lots.some((l) => l.numero === b.numero)) return json({ error: 'conflit', message: 'Ce numéro de lot existe déjà.' }, 409);
      return json(this.view(this.addLot({ ...b, qualityStatus: 'EN_ATTENTE' })), 201);
    }
    if (p === '/api/lots') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const st = url.searchParams.get('status');
      return json(this.lots.filter((l) => (!st || l.qualityStatus === st) && (!q || `${l.numero} ${l.lotFournisseur ?? ''}`.toLowerCase().includes(q)))
        .map((l) => this.view(l)));
    }
    if ((mm = p.match(/^\/api\/lots\/([^/]+)(\/[a-z.]+)?$/))) {
      const lot = this.lots.find((l) => l.id === mm![1]);
      if (!lot) return json({ error: 'introuvable', message: 'Lot introuvable.' }, 404);
      const sub = mm[2] ?? '';
      if (sub === '') return json(this.view(lot));
      if (sub === '/status' && m === 'POST') {
        const b = body();
        if (b.status === 'DEROGATION' && !b.reason) return json({ error: 'motif_requis', message: 'La dérogation exige un motif.' }, 400);
        this.statusCalls.push({ id: lot.id, status: b.status, reason: b.reason });
        this.setLotStatus(lot, b.status, b.reason);
        return json(this.view(lot));
      }
      if (sub === '/trace') return json(this.trace(lot.id, url.searchParams.get('direction') ?? 'down', Number(url.searchParams.get('depth') ?? 10)));
      if (sub === '/recall') return json(this.recall(lot.id));
      if (sub === '/recall.pdf') {
        return route.fulfill({ status: 200, body: PDF, headers: { 'content-type': 'application/pdf',
          'content-disposition': `attachment; filename="rappel-${lot.numero}.pdf"` } });
      }
    }
    if (p === '/api/control-plans') {
      if (m === 'POST') { const b = { ...body(), id: `cp${this.plans.length + 1}`, version: 1 }; this.plans.push(b); return json(b, 201); }
      return json(this.plans);
    }
    if ((mm = p.match(/^\/api\/control-plans\/([^/]+)$/)) && m === 'PUT') {
      const i = this.plans.findIndex((x) => x['id'] === mm![1]);
      this.plans[i] = { ...body(), id: mm[1], version: Number(this.plans[i]['version']) + 1 };
      return json(this.plans[i]);
    }
    if (p === '/api/inspections/spc') return json(this.spc());
    if (p === '/api/inspections' && m === 'POST') {
      const b = body();
      const i: Insp = { id: `ins-${this.inspections.length + 1}`, lotId: b.lotId, controlPlanId: b.controlPlanId, point: b.point,
        planVersion: Number(this.plans.find((x) => x['id'] === b.controlPlanId)?.['version'] ?? 1), status: 'A_FAIRE',
        sampleSize: 13, codeLetter: 'F', ac: 0, re: 1, results: [], decision: null, createdAt: this.now(), authorName: 'Salma Bennani' };
      this.inspections.push(i);
      return json(this.inspView(i), 201);
    }
    if (p === '/api/inspections') {
      const st = url.searchParams.get('status');
      const lotId = url.searchParams.get('lotId');
      return json(this.inspections.filter((i) => (!st || i.status === st) && (!lotId || i.lotId === lotId)).map((i) => this.inspView(i)));
    }
    if ((mm = p.match(/^\/api\/inspections\/([^/]+)(\/results|\/decision)?$/))) {
      const i = this.inspections.find((x) => x.id === mm![1]);
      if (!i) return json({ error: 'introuvable', message: 'Contrôle introuvable.' }, 404);
      if (mm[2] === '/results' && m === 'PUT') {
        if (!req.headers()['idempotency-key']) return json({ error: 'cle', message: 'Idempotency-Key requise.' }, 400);
        i.results = body();
        i.status = 'EN_COURS';
        return json(this.inspView(i));
      }
      if (mm[2] === '/decision' && m === 'POST') {
        const b = body();
        i.decision = b.decision;
        i.status = 'TERMINE';
        this.setLotStatus(this.lots.find((l) => l.id === i.lotId)!, b.decision, b.comment || `Contrôle ${i.point}`);
        return json(this.inspView(i));
      }
      return json(this.inspView(i));
    }
    return super.handle(route);
  }

  private inspView(i: Insp) {
    return { ...i, lot: { id: i.lotId, numero: this.lots.find((l) => l.id === i.lotId)?.numero } };
  }
}
