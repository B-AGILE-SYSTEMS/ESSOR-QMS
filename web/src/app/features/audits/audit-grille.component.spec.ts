import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { QmsRecord } from '../../core/api.models';
import { checkDocFile } from '../documents/doc-versions.component';
import { docTone } from '../documents/documents-liste.component';
import { flatten } from '../fiches/record-requirements.component';
import { AuditGrilleComponent } from './audit-grille.component';
import { byMonth } from './audits-programme.component';


/** Attend qu'une requête soit émise (chaînes async des composants), puis la renvoie. */
async function req(http: HttpTestingController, m: Parameters<HttpTestingController['match']>[0]): Promise<TestRequest> {
  for (let i = 0; i < 50; i++) {
    const found = http.match(m);
    if (found.length) return found[0];
    await new Promise((r) => setTimeout(r, 0));
  }
  return http.expectOne(m);
}

async function settle(f: { whenStable(): Promise<unknown>; detectChanges(): void }): Promise<void> {
  for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0));
  await f.whenStable();
  f.detectChanges();
}

const AUDIT: QmsRecord = {
  id: 'a1', reference: 'AUD-2026-0001', type: 'AUDIT', status: 'EN_COURS', statusLabel: 'En cours', title: 'Audit',
  description: null, data: { standardIds: ['s1'] }, createdBy: 'u', children: [], availableTransitions: [],
};
const Q = [
  { id: 'q1', requirementCode: '8.1.2', question: 'Q1', result: null },
  { id: 'q2', requirementCode: '10.2', question: 'Q2', result: 'conforme' },
];

describe('AuditGrilleComponent', () => {
  let fixture: ComponentFixture<AuditGrilleComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function setup(rec: QmsRecord): Promise<void> {
    TestBed.configureTestingModule({
      imports: [AuditGrilleComponent],
      providers: [provideRouter([]), provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditGrilleComponent);
    fixture.componentRef.setInput('record', rec);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('exécute une question : un écart clos affiche la NC créée', async () => {
    await setup(AUDIT);
    (await req(http, '/api/audits/a1')).flush({ id: 'a1', questions: Q });
    await settle(fixture);
    expect(el().querySelectorAll('.q').length).toBe(2);
    expect(el().querySelector('.sum')!.textContent).toContain('1/2');
    const q1 = el().querySelectorAll('.q')[0];
    (Array.from(q1.querySelectorAll<HTMLButtonElement>('.chip')).find((b) => b.textContent === 'Écart majeur')!).click();
    (q1.querySelector('input[type=checkbox]') as HTMLInputElement).click();
    fixture.detectChanges();
    (Array.from(q1.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.includes('Enregistrer'))!).click();
    const put = (await req(http, '/api/audits/a1/answers/q1'));
    expect(put.request.body).toEqual({ result: 'ecart_majeur', comment: '', evidence: '', closed: true });
    put.flush({ ...Q[0], result: 'ecart_majeur', ncId: 'nc-7', ncReference: 'NC-2026-0007' });
    await settle(fixture);
    expect(el().querySelector('.q .nc')!.textContent).toContain('NC-2026-0007');
    expect(el().querySelector('[role=status]')!.textContent).toContain('NC NC-2026-0007 créée');
    expect(el().textContent).toContain('Constats');
  });

  it('planifié : génère la grille, modifie et supprime une question ; rapport téléchargeable en RAPPORT', async () => {
    await setup({ ...AUDIT, status: 'PLANIFIE' });
    (await req(http, '/api/standards')).flush([{ id: 's1', code: 'ISO45001', title: 'ISO 45001', version: '2018' }]);
    await fixture.whenStable();
    (await req(http, '/api/audits/a1')).flush({ id: 'a1', questions: [] });
    await settle(fixture);
    expect(el().textContent).toContain('ISO 45001:2018');
    (el().querySelector('.gen button[type=submit]') as HTMLButtonElement).click();
    const gen = (await req(http, '/api/audits/a1/checklist/generate'));
    expect(gen.request.body).toEqual({ standardIds: ['s1'] });
    gen.flush(Q);
    await settle(fixture);
    const input = el().querySelector('.qt') as HTMLInputElement;
    input.value = 'Q1 modifiée';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    (await req(http, '/api/audits/a1/questions/q1')).flush({ ...Q[0], question: 'Q1 modifiée' });
    await fixture.whenStable();
    (el().querySelector('[aria-label="Supprimer la question 2"]') as HTMLButtonElement).click();
    (await req(http, '/api/audits/a1/questions/q2')).flush(null);
    await settle(fixture);
    expect(el().querySelectorAll('.q').length).toBe(1);
    fixture.componentRef.setInput('record', { ...AUDIT, status: 'RAPPORT' });
    fixture.detectChanges();
    (await req(http, '/api/audits/a1')).flush({ id: 'a1', questions: Q });
    await settle(fixture);
    expect(el().querySelector('a.rp')!.getAttribute('href')).toBe('/api/audits/a1/report.pdf');
  });
});

describe('Utilitaires M3 des écrans', () => {
  it('range les audits par mois de leur date prévue', () => {
    const base = { reference: '', type: 'AUDIT', status: '', statusLabel: '', title: '' };
    const m = byMonth([
      { ...base, id: 'b', plannedDate: '2026-11-21' }, { ...base, id: 'a', plannedDate: '2026-11-03' },
      { ...base, id: 'c', datePrevue: '2026-03-12' }, { ...base, id: 'x' },
    ]);
    expect(m[10].map((a) => a.id)).toEqual(['a', 'b']);
    expect(m[2].map((a) => a.id)).toEqual(['c']);
    expect(m.flat().length).toBe(3);
  });

  it('contrôle les fichiers de version et aplatit un arbre', () => {
    expect(checkDocFile(new File(['x'], 'a.txt', { type: 'text/plain' }))).toContain('Format refusé');
    expect(checkDocFile(new File(['x'], 'a.docx', { type: '' }))).toBeNull();
    const big = { name: 'a.pdf', type: 'application/pdf', size: 21 * 1024 * 1024 } as File;
    expect(checkDocFile(big)).toContain('20 Mo');
    expect(flatten([{ id: 'r', code: '8', title: '', auditable: false, children: [{ id: 'c', code: '8.1', title: '', auditable: true }] }])
      .map((n) => `${n.code}:${n.depth}`)).toEqual(['8:0', '8.1:1']);
    expect([docTone('DIFFUSE'), docTone('PERIME'), docTone('REDACTION'), docTone('VERIFICATION')]).toEqual(['ok', 'crit', 'info', 'warn']);
  });
});
