import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  AuditQuestion, Requirement, buildTree, chapterOf, conformityByChapter, isGap, itemsOf, plannedDateOf, standardLabel, versionTransitions,
} from './m3.models';
import { AuditsService, DocumentsService, StandardsService, auditReportUrl, versionFileUrl } from './m3.service';

describe('Modèles M3', () => {
  it('construit un arbre depuis une liste plate, trié par ordre puis code', () => {
    const flat: Requirement[] = [
      { id: 'c', code: '8.1.2', title: 'c', parentId: 'b', auditable: true, order: 2 },
      { id: 'a', code: '10', title: 'a', parentId: null, auditable: false, order: 5 },
      { id: 'b', code: '8.1', title: 'b', parentId: 'r', auditable: false, order: 1 },
      { id: 'r', code: '8', title: 'r', parentId: null, auditable: false, order: 0 },
    ];
    const t = buildTree(flat);
    expect(t.map((n) => n.code)).toEqual(['8', '10']);
    expect(t[0].children![0].children![0].code).toBe('8.1.2');
  });

  it('garde un arbre déjà imbriqué', () => {
    const t = buildTree([{ id: 'r', code: '4', title: 'r', auditable: false, children: [
      { id: 'y', code: '4.10', title: 'y', auditable: true }, { id: 'x', code: '4.2', title: 'x', auditable: true }] }]);
    expect(t[0].children!.map((c) => c.code)).toEqual(['4.2', '4.10']);
  });

  it('calcule la conformité par chapitre hors N/A et observations', () => {
    const qs = [
      { id: '1', question: '', requirementCode: '8.1.2', result: 'conforme' },
      { id: '2', question: '', requirementCode: '8.2', result: 'ecart_majeur' },
      { id: '3', question: '', requirementCode: '8.3', result: 'na' },
      { id: '4', question: '', requirementCode: '10.2', result: 'observation' },
    ] as AuditQuestion[];
    expect(conformityByChapter(qs)).toEqual([
      { chapter: '8', rate: 50, answered: 3, total: 3 },
      { chapter: '10', rate: null, answered: 1, total: 1 },
    ]);
    expect(chapterOf('§7.5')).toBe('7');
    expect(chapterOf(null)).toBe('—');
    expect(isGap('ecart_mineur')).toBeTrue();
    expect(isGap('observation')).toBeFalse();
  });

  it('lit listes, dates prévues, libellés et transitions de version', () => {
    expect(itemsOf([1])).toEqual([1]);
    expect(itemsOf({ items: [2] })).toEqual([2]);
    expect(itemsOf(null)).toEqual([]);
    expect(plannedDateOf({ id: 'a', reference: '', type: 'AUDIT', status: '', statusLabel: '', title: '', data: { plannedDate: '2026-11-21' } }))
      .toBe('2026-11-21');
    expect(standardLabel({ id: 's', code: 'X', title: 'ISO 45001', version: '2018' })).toBe('ISO 45001:2018');
    expect(standardLabel({ id: 's', code: 'X', title: 'Interne' })).toBe('Interne');
    const v = { number: '1', status: 'APPROBATION', fileName: 'a.pdf', contentType: 'application/pdf', size: 1, createdAt: '' };
    expect(versionTransitions(v).map((t) => t.code)).toEqual(['approuver', 'refuser_approbation']);
    expect(versionTransitions({ ...v, availableTransitions: [{ code: 'x', label: 'X', missing: [] }] }).map((t) => t.code)).toEqual(['x']);
  });
});

describe('Services M3', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withXhr()), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('référentiels : liste, exigences, détail, import multipart avec simulation, liens', async () => {
    const s = TestBed.inject(StandardsService);
    const l = s.list();
    http.expectOne('/api/standards').flush({ items: [{ id: 's1' }], total: 1, page: 0, size: 20 });
    expect((await l).length).toBe(1);
    const r = s.requirements('s 1');
    http.expectOne('/api/standards/s%201/requirements').flush([]);
    expect(await r).toEqual([]);
    const d = s.requirement('r1');
    http.expectOne('/api/requirements/r1').flush({ id: 'r1' });
    await d;
    const file = new File(['code;titre'], 'iso.csv', { type: 'text/csv' });
    const imp = s.import(file, { code: 'ISO45001', title: 'ISO 45001', version: '2018' }, true);
    const req = http.expectOne((x) => x.url === '/api/standards/import');
    expect(req.request.params.get('dryRun')).toBe('true');
    const form = req.request.body as FormData;
    expect(form.get('standardCode')).toBe('ISO45001');
    expect(form.get('standardVersion')).toBe('2018');
    expect((form.get('file') as File).name).toBe('iso.csv');
    req.flush({ created: 3, updated: 0, errors: [], applied: false, dryRun: true });
    expect((await imp).created).toBe(3);
    const real = s.import(file, { code: 'A', title: 'A', version: '' }, false);
    const req2 = http.expectOne((x) => x.url === '/api/standards/import');
    expect(req2.request.params.has('dryRun')).toBeFalse();
    req2.flush({ created: 1, updated: 0, errors: [] });
    await real;
    const link = s.linkRequirements('nc-1', ['r1']);
    const put = http.expectOne('/api/records/nc-1/requirements');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual(['r1']);
    put.flush(null);
    await link;
  });

  it('audits : programme filtré, grille lue dans la fiche, génération, édition, suppression, réponse idempotente', async () => {
    const a = TestBed.inject(AuditsService);
    const p = a.program(2026, { mine: true, status: ['PLANIFIE', 'EN_COURS'] });
    const pr = http.expectOne((x) => x.url === '/api/audits');
    expect(pr.request.params.get('year')).toBe('2026');
    expect(pr.request.params.get('auditor')).toBe('me');
    expect(pr.request.params.get('status')).toBe('PLANIFIE,EN_COURS');
    pr.flush([]);
    await p;
    const c = a.checklist('a1');
    http.expectOne('/api/audits/a1').flush({ id: 'a1', questions: [{ id: 'q1', question: 'Q' }] });
    expect((await c)[0].id).toBe('q1');
    const g = a.generate('a1', ['s1']);
    const gr = http.expectOne('/api/audits/a1/checklist/generate');
    expect(gr.request.body).toEqual({ standardIds: ['s1'] });
    gr.flush([]);
    await g;
    const g2 = a.generate('a1', ['s1'], ['r1']);
    const g2r = http.expectOne('/api/audits/a1/checklist/generate');
    expect(g2r.request.body).toEqual({ standardIds: ['s1'], requirementIds: ['r1'] });
    g2r.flush([]);
    await g2;
    const e = a.editQuestion('a1', 'q1', 'Nouvelle');
    const er = http.expectOne('/api/audits/a1/questions/q1');
    expect(er.request.method).toBe('PATCH');
    er.flush({ id: 'q1', question: 'Nouvelle' });
    await e;
    const del = a.deleteQuestion('a1', 'q1');
    const dr = http.expectOne('/api/audits/a1/questions/q1');
    expect(dr.request.method).toBe('DELETE');
    dr.flush(null);
    await del;
    const ans = a.answer('a1', 'q1', { result: 'ecart_majeur', comment: 'c', evidence: '', closed: true }, 'k-1');
    const ar = http.expectOne('/api/audits/a1/answers/q1');
    expect(ar.request.method).toBe('PUT');
    expect(ar.request.headers.get('Idempotency-Key')).toBe('k-1');
    expect(ar.request.body.closed).toBeTrue();
    ar.flush({ id: 'q1', question: 'Q', ncId: 'nc-9' });
    expect((await ans).ncId).toBe('nc-9');
    expect(auditReportUrl('a 1')).toBe('/api/audits/a%201/report.pdf');
  });

  it('documents : liste, création, versions, transitions par numéro, accusés de lecture', async () => {
    const d = TestBed.inject(DocumentsService);
    const l = d.list({ status: 'DIFFUSE', type: '', q: 'PR' });
    const lr = http.expectOne((x) => x.url === '/api/documents');
    expect(lr.request.params.get('status')).toBe('DIFFUSE');
    expect(lr.request.params.has('type')).toBeFalse();
    lr.flush([{ id: 'd1', currentVersion: { id: 'v9', number: 2, fileName: 'a.pdf' } }]);
    expect((await l)[0].currentVersion!.number).toBe(2);
    const c = d.create({ siteId: 's1', title: 'T', description: '', data: {} });
    const cr = http.expectOne('/api/documents');
    expect(cr.request.method).toBe('POST');
    cr.flush({ id: 'd2' });
    expect((await c).id).toBe('d2');
    const v = d.versions('d1');
    http.expectOne('/api/documents/d1/versions').flush([]);
    await v;
    const up = d.addVersion('d1', new File(['%PDF'], 'v.pdf', { type: 'application/pdf' }), 'note');
    const ur = http.expectOne('/api/documents/d1/versions');
    expect((ur.request.body as FormData).get('note')).toBe('note');
    ur.flush({});
    await up;
    const t = d.versionTransition('d1', '2', 'approuver');
    http.expectOne('/api/documents/d1/versions/2/transitions/approuver').flush(null);
    await t;
    const ack = d.ack('d1', '2');
    http.expectOne('/api/documents/d1/versions/2/ack').flush(null);
    await ack;
    const acks = d.acks('d1', '2');
    http.expectOne('/api/documents/d1/versions/2/acks').flush({ read: [], pending: [] });
    await acks;
    expect(versionFileUrl('d1', '2')).toBe('/api/documents/d1/versions/2/file');
  });
});
