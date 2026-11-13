import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  EvaluationGrid, Impact, QuarantineResult, toQuarantine, Instrument, M4Kpis, NewCalibration, NewRisk, PartnerEvaluation, Regulation, RegEvaluation, Risk, RiskMatrix,
  RiskScale, Scorecard, SiteCompliance, listOf, toCalibrationBody, toCompliance, toEvaluation, toGrid, toGridBody, toImpact, toInstrument,
  toM4Kpis, toMatrix, toRegEvaluation, toRegulation, toRisk, toRiskBody, toRiskScale, toRiskScaleBody, toScorecard,
} from './m4.models';

const enc = encodeURIComponent;
type Raw = Record<string, unknown>;
const params = (f: Record<string, string | null | undefined>): Record<string, string> =>
  Object.fromEntries(Object.entries(f).filter(([, v]) => !!v)) as Record<string, string>;

/** Risques et opportunités, échelles de cotation (contrat M4). */
@Injectable({ providedIn: 'root' })
export class RisksService {
  private readonly http = inject(HttpClient);
  async list(f: { siteId?: string | null; type?: string | null } = {}): Promise<Risk[]> {
    return listOf(await firstValueFrom(this.http.get<unknown>('/api/risks', { params: params(f) }))).map(toRisk);
  }
  async get(id: string): Promise<Risk> { return toRisk(await firstValueFrom(this.http.get<Raw>(`/api/risks/${enc(id)}`))); }
  async create(r: NewRisk): Promise<Risk> { return toRisk(await firstValueFrom(this.http.post<Raw>('/api/risks', toRiskBody(r)))); }
  async update(id: string, r: NewRisk): Promise<Risk> { return toRisk(await firstValueFrom(this.http.put<Raw>(`/api/risks/${enc(id)}`, toRiskBody(r)))); }
  async matrix(siteId: string | null, type: string | null): Promise<RiskMatrix> {
    return toMatrix(await firstValueFrom(this.http.get<Raw>('/api/risks/matrix', { params: params({ siteId, type }) })));
  }
  async scales(): Promise<RiskScale[]> { return listOf(await firstValueFrom(this.http.get<unknown>('/api/risk-scales'))).map(toRiskScale); }
  async saveScale(s: RiskScale): Promise<RiskScale> {
    const url = s.id ? `/api/risk-scales/${enc(s.id)}` : '/api/risk-scales';
    return toRiskScale(await firstValueFrom(this.http.put<Raw>(url, toRiskScaleBody(s))));
  }
}
export const duerPdfUrl = (siteId: string | null): string => `/api/risks/duer.pdf${siteId ? `?siteId=${enc(siteId)}` : ''}`;

/** Veille réglementaire. */
@Injectable({ providedIn: 'root' })
export class RegulationsService {
  private readonly http = inject(HttpClient);
  async list(f: { domain?: string | null; siteId?: string | null; status?: string | null } = {}): Promise<Regulation[]> {
    return listOf(await firstValueFrom(this.http.get<unknown>('/api/regulations', { params: params(f) }))).map(toRegulation);
  }
  async get(id: string): Promise<Regulation> { return toRegulation(await firstValueFrom(this.http.get<Raw>(`/api/regulations/${enc(id)}`))); }
  async evaluate(id: string, b: { siteId: string; result: string; evidence: string; comment: string }): Promise<RegEvaluation> {
    return toRegEvaluation(await firstValueFrom(this.http.post<Raw>(`/api/regulations/${enc(id)}/evaluations`, b)));
  }
  async compliance(): Promise<SiteCompliance[]> { return toCompliance(await firstValueFrom(this.http.get<unknown>('/api/regulations/compliance'))); }
}

/** Métrologie : parc, étalonnages, effet instrument. */
@Injectable({ providedIn: 'root' })
export class InstrumentsService {
  private readonly http = inject(HttpClient);
  async list(f: { siteId?: string | null; status?: string | null; selectable?: boolean } = {}): Promise<Instrument[]> {
    const p = params({ siteId: f.siteId, status: f.status, selectable: f.selectable ? 'true' : null });
    return listOf(await firstValueFrom(this.http.get<unknown>('/api/instruments', { params: p }))).map(toInstrument);
  }
  async get(id: string): Promise<Instrument> { return toInstrument(await firstValueFrom(this.http.get<Raw>(`/api/instruments/${enc(id)}`))); }
  async calibrate(id: string, c: NewCalibration): Promise<unknown> {
    return firstValueFrom(this.http.post<unknown>(`/api/instruments/${enc(id)}/calibrations`, toCalibrationBody(c)));
  }
  async impact(id: string, since?: string | null): Promise<Impact> {
    return toImpact(await firstValueFrom(this.http.get<Raw>(`/api/instruments/${enc(id)}/impact`, { params: params({ since }) })));
  }
  /** Mise en quarantaine des lots de l'effet instrument (le serveur passe par l'API lots M3b et émet les webhooks). */
  async quarantine(id: string, reason: string, since?: string | null): Promise<QuarantineResult> {
    return toQuarantine(await firstValueFrom(this.http.post<Raw>(`/api/instruments/${enc(id)}/impact/quarantine`, { reason, since: since ?? null })));
  }
}

/** Évaluation clients et fournisseurs. */
@Injectable({ providedIn: 'root' })
export class EvaluationsService {
  private readonly http = inject(HttpClient);
  async grids(): Promise<EvaluationGrid[]> { return listOf(await firstValueFrom(this.http.get<unknown>('/api/evaluation-grids'))).map(toGrid); }
  async saveGrid(g: EvaluationGrid): Promise<EvaluationGrid> {
    const r = g.id ? this.http.put<Raw>(`/api/evaluation-grids/${enc(g.id)}`, toGridBody(g)) : this.http.post<Raw>('/api/evaluation-grids', toGridBody(g));
    return toGrid(await firstValueFrom(r));
  }
  async evaluations(partnerId: string): Promise<PartnerEvaluation[]> {
    return listOf(await firstValueFrom(this.http.get<unknown>(`/api/partners/${enc(partnerId)}/evaluations`))).map(toEvaluation);
  }
  async evaluate(partnerId: string, b: { evaluationId?: string | null; gridId: string; period: string; scores: Record<string, number>; comment: string }): Promise<PartnerEvaluation> {
    return toEvaluation(await firstValueFrom(this.http.post<Raw>(`/api/partners/${enc(partnerId)}/evaluations`, b)));
  }
  async scorecard(partnerId: string): Promise<Scorecard> {
    return toScorecard(await firstValueFrom(this.http.get<Raw>(`/api/partners/${enc(partnerId)}/scorecard`)));
  }
  async campaign(b: { gridId: string; partnerIds: string[]; period: string }): Promise<PartnerEvaluation[]> {
    const r = await firstValueFrom(this.http.post<unknown>('/api/evaluation-campaigns', { grid: b.gridId, gridId: b.gridId, partnerIds: b.partnerIds, period: b.period }));
    const o = (r ?? {}) as Raw;
    return listOf(Array.isArray(r) ? r : o['evaluations'] ?? o['items'] ?? []).map(toEvaluation);
  }
}

/** KPI M4 du tableau de bord (champs ajoutés à `/api/dashboard/summary`). */
@Injectable({ providedIn: 'root' })
export class DashboardM4Service {
  private readonly http = inject(HttpClient);
  async kpis(): Promise<M4Kpis> { return toM4Kpis(await firstValueFrom(this.http.get<Raw>('/api/dashboard/summary'))); }
}
