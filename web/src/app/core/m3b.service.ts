import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Page } from './api.models';
import { itemsOf } from './m3.models';
import {
  Article, ControlPlan, Decision, ImportReport, Inspection, InspectionResult, LinkKind, Lot, NewLot, Partner, Recall, Spc, TraceNode,
  WebhookConfig, toInspection, toLot, toRecall, toSpc, toTrace,
} from './m3b.models';
import { newIdempotencyKey } from './records.service';

const enc = encodeURIComponent;
type List<T> = T[] | Page<T>;
type Raw = Record<string, unknown>;

/** Lots, statut qualité, généalogie et rappel (contrat M3b). */
@Injectable({ providedIn: 'root' })
export class LotsService {
  private readonly http = inject(HttpClient);

  async list(f: { q?: string; status?: string; articleId?: string } = {}): Promise<Lot[]> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(f)) if (v) params[k] = v;
    return itemsOf(await firstValueFrom(this.http.get<List<Raw>>('/api/lots', { params }))).map(toLot);
  }

  async get(id: string): Promise<Lot> {
    return toLot(await firstValueFrom(this.http.get<Raw>(`/api/lots/${enc(id)}`)));
  }

  async create(body: NewLot): Promise<Lot> {
    return toLot(await firstValueFrom(this.http.post<Raw>('/api/lots', body)));
  }

  async setStatus(id: string, status: string, reason: string): Promise<Lot> {
    return toLot(await firstValueFrom(this.http.post<Raw>(`/api/lots/${enc(id)}/status`, { status, reason })));
  }

  addLink(id: string, body: { kind: LinkKind; targetLotId?: string; partnerId?: string; quantity: number; reference: string }): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/lots/${enc(id)}/links`, body));
  }

  async trace(id: string, direction: 'up' | 'down', depth = 10): Promise<TraceNode> {
    return toTrace(await firstValueFrom(
      this.http.get<Raw>(`/api/lots/${enc(id)}/trace`, { params: { direction, depth: String(depth) } })));
  }

  async recall(id: string): Promise<Recall> {
    return toRecall(await firstValueFrom(this.http.get<Raw>(`/api/lots/${enc(id)}/recall`)));
  }
}

export function recallPdfUrl(lotId: string): string {
  return `/api/lots/${enc(lotId)}/recall.pdf`;
}

/** Plans de contrôle versionnés. */
@Injectable({ providedIn: 'root' })
export class ControlPlansService {
  private readonly http = inject(HttpClient);

  async list(): Promise<ControlPlan[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<ControlPlan>>('/api/control-plans')));
  }

  save(p: ControlPlan): Promise<ControlPlan> {
    return p.id
      ? firstValueFrom(this.http.put<ControlPlan>(`/api/control-plans/${enc(p.id)}`, p))
      : firstValueFrom(this.http.post<ControlPlan>('/api/control-plans', p));
  }
}

/** Contrôles (inspections) et carte de contrôle. */
@Injectable({ providedIn: 'root' })
export class InspectionsService {
  private readonly http = inject(HttpClient);

  async list(f: { status?: string; lotId?: string } = {}): Promise<Inspection[]> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(f)) if (v) params[k] = v;
    return itemsOf(await firstValueFrom(this.http.get<List<Raw>>('/api/inspections', { params }))).map(toInspection);
  }

  async get(id: string): Promise<Inspection> {
    return toInspection(await firstValueFrom(this.http.get<Raw>(`/api/inspections/${enc(id)}`)));
  }

  async create(lotId: string, controlPlanId: string, point: string): Promise<Inspection> {
    return toInspection(await firstValueFrom(this.http.post<Raw>('/api/inspections', { lotId, controlPlanId, point })));
  }

  saveResults(id: string, results: InspectionResult[], key: string = newIdempotencyKey()): Promise<unknown> {
    return firstValueFrom(this.http.put(`/api/inspections/${enc(id)}/results`, results, { headers: { 'Idempotency-Key': key } }));
  }

  decide(id: string, decision: Decision, comment: string): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/inspections/${enc(id)}/decision`, { decision, comment }));
  }

  async spc(controlPlanId: string, characteristic: string): Promise<Spc> {
    return toSpc(await firstValueFrom(this.http.get<Raw>('/api/inspections/spc', { params: { controlPlanId, characteristic } })));
  }
}

/** Données de base (articles, partenaires) et connecteur webhook. */
@Injectable({ providedIn: 'root' })
export class MasterDataService {
  private readonly http = inject(HttpClient);

  async articles(): Promise<Article[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<Article>>('/api/master-data/articles')));
  }

  async partners(): Promise<Partner[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<Partner>>('/api/master-data/partners')));
  }

  import(kind: 'articles' | 'partners', file: File, dryRun: boolean): Promise<ImportReport> {
    const form = new FormData();
    form.append('file', file, file.name);
    return firstValueFrom(this.http.post<ImportReport>(`/api/master-data/${kind}/import`, form, { params: dryRun ? { dryRun: 'true' } : {} }));
  }

  webhook(): Promise<WebhookConfig> {
    return firstValueFrom(this.http.get<WebhookConfig>('/api/connectors/webhook'));
  }

  saveWebhook(c: WebhookConfig): Promise<WebhookConfig> {
    return firstValueFrom(this.http.put<WebhookConfig>('/api/connectors/webhook', c));
  }
}
