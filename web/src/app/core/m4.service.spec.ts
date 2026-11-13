import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DashboardM4Service, EvaluationsService, InstrumentsService, RegulationsService, RisksService, duerPdfUrl } from './m4.service';

describe('Services M4 (contrat)', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('risques : liste filtrée, matrice, création, échelles', async () => {
    const api = TestBed.inject(RisksService);
    const l = api.list({ siteId: 's1', type: null });
    http.expectOne('/api/risks?siteId=s1').flush({ items: [{ id: 'r1', hazard: 'H' }] });
    expect((await l)[0].hazard).toBe('H');
    const m = api.matrix('s1', 'sst');
    http.expectOne('/api/risks/matrix?siteId=s1&type=sst').flush({ cells: [] });
    expect((await m).cells).toEqual([]);
    const c = api.create({ type: 'sst', siteId: 's1', unit: '', hazard: 'H', situation: '', measures: '', gross: { G: 1 }, residual: {}, reviewDate: null });
    const req = http.expectOne('/api/risks');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'r2' });
    expect((await c).id).toBe('r2');
    const s = api.saveScale({ id: 'x', code: 'C', label: '', dimensions: [], formula: [], thresholds: [] });
    http.expectOne('/api/risk-scales/x').flush({ id: 'x' });
    await s;
    expect(duerPdfUrl('s 1')).toBe('/api/risks/duer.pdf?siteId=s%201');
    expect(duerPdfUrl(null)).toBe('/api/risks/duer.pdf');
  });

  it('veille, instruments, évaluations, campagne, KPI', async () => {
    const reg = TestBed.inject(RegulationsService);
    const e = reg.evaluate('rg1', { siteId: 's1', result: 'non_conforme', evidence: '', comment: '' });
    const r = http.expectOne('/api/regulations/rg1/evaluations');
    expect(r.request.body.result).toBe('non_conforme');
    r.flush({ id: 'e1', result: 'non_conforme', actionId: 'a1' });
    expect((await e).actions[0].id).toBe('a1');
    const ins = TestBed.inject(InstrumentsService);
    const im = ins.impact('i1');
    http.expectOne('/api/instruments/i1/impact').flush({ inspections: [], lots: [] });
    expect((await im).lots).toEqual([]);
    const ev = TestBed.inject(EvaluationsService);
    const camp = ev.campaign({ gridId: 'g1', partnerIds: ['p1'], period: '2026-T4' });
    const cr = http.expectOne('/api/evaluation-campaigns');
    expect(cr.request.body).toEqual({ grid: 'g1', gridId: 'g1', partnerIds: ['p1'], period: '2026-T4' });
    cr.flush([{ id: 'pe', period: '2026-T4' }]);
    expect((await camp).length).toBe(1);
    const sc = ev.scorecard('p1');
    http.expectOne('/api/partners/p1/scorecard').flush({ evaluations: [] });
    expect((await sc).evaluations).toEqual([]);
    const k = TestBed.inject(DashboardM4Service).kpis();
    http.expectOne('/api/dashboard/summary').flush({ regulatoryCompliance: 50, calibrationsDue15: 1 });
    expect((await k).regulatoryCompliance).toBe(50);
  });
});
