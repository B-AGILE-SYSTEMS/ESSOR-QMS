import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { toLot } from '../../core/m3b.models';
import { CarteControleComponent } from '../controles/carte-controle.component';
import { ControleSaisieComponent } from '../controles/controle-saisie.component';
import { LotFicheComponent } from './lot-fiche.component';
import { RappelComponent } from './rappel.component';

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

const LOT = { id: 'l1', numero: 'R-1', article: { id: 'a', code: 'MP', designation: 'Tube' }, qualityStatus: 'LIBERE', historique: [] };
const PLAN = { id: 'cp1', code: 'CP', libelle: 'Réception', point: 'RECEPTION', target: { type: 'article', value: 'MP' }, sampling: { mode: 'fixe', taille: 2 },
  characteristics: [{ code: 'D', libelle: 'Diamètre', type: 'mesure', nominal: 12, tolMin: 11.9, tolMax: 12.1, unite: 'mm', instrumentRequis: false, criticite: 'majeure' },
    { code: 'A', libelle: 'Aspect', type: 'attribut', nominal: null, tolMin: null, tolMax: null, unite: null, instrumentRequis: false, criticite: 'mineure' }] };

describe('Écrans M3b', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(withXhr()), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('rappel : affiche clients et bloque le stock restant après confirmation', async () => {
    const f = TestBed.createComponent(RappelComponent);
    f.componentRef.setInput('lot', toLot(LOT));
    f.detectChanges();
    (await req(http, '/api/lots/l1/recall')).flush({
      lotsImpactes: [{ id: 'l2', numero: 'PF-1', qualityStatus: 'LIBERE' }],
      clients: [{ partner: { id: 'c', code: 'C1', nom: 'Client 1' }, quantite: 10, references: ['BL-1'] }],
      quantites: { enStock: 5, enCours: 0, expedie: 10 } });
    await settle(f);
    const el: HTMLElement = f.nativeElement;
    expect(el.textContent).toContain('C1 — Client 1');
    expect(el.querySelector('a.btn')!.getAttribute('href')).toBe('/api/lots/l1/recall.pdf');
    (el.querySelector('footer .btn.pri') as HTMLButtonElement).click();
    f.detectChanges();
    expect(el.querySelector('[role=alertdialog]')!.textContent).toContain('Bloquer 2 lot(s)');
    (el.querySelector('[role=alertdialog] .btn.pri') as HTMLButtonElement).click();
    const r1 = await req(http, '/api/lots/l1/status');
    expect(r1.request.body).toEqual({ status: 'QUARANTAINE', reason: 'Rappel fournisseur — lot R-1' });
    r1.flush({ ...LOT, qualityStatus: 'QUARANTAINE' });
    (await req(http, '/api/lots/l2/status')).flush({ id: 'l2', qualityStatus: 'QUARANTAINE' });
    await settle(f);
    expect(el.querySelector('.ok-msg')!.textContent).toContain('2 lot(s) bloqué(s)');
  });

  it('fiche lot : généalogie, contrôles et changement de statut', async () => {
    const f = TestBed.createComponent(LotFicheComponent);
    f.componentRef.setInput('id', 'l1');
    f.detectChanges();
    (await req(http, '/api/lots/l1')).flush(LOT);
    (await req(http, (r) => r.url === '/api/lots/l1/trace')).flush({ lot: LOT, children: [
      { link: { kind: 'shipped_to', quantity: 3, reference: 'BL-9', partner: { id: 'c', nom: 'Client' } } }] });
    (await req(http, (r) => r.url === '/api/inspections')).flush([]);
    (await req(http, '/api/control-plans')).flush([PLAN]);
    await settle(f);
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelectorAll('[role=treeitem]').length).toBe(2);
    expect(el.textContent).toContain('BL-9');
    (el.querySelectorAll('.chip')[1] as HTMLButtonElement).click();
    const up = await req(http, (r) => r.url === '/api/lots/l1/trace');
    expect(up.request.params.get('direction')).toBe('up');
    up.flush({ lot: LOT, children: [] });
    (el.querySelector('form[aria-label="Changer le statut qualité"] button') as HTMLButtonElement).click();
    const st = await req(http, '/api/lots/l1/status');
    expect(st.request.body.status).toBe('QUARANTAINE');
    st.flush({ ...LOT, qualityStatus: 'QUARANTAINE' });
    await settle(f);
    expect(el.querySelector('[data-testid=lot-status]')!.textContent).toContain('Quarantaine');
  });

  it('saisie de contrôle : verdict, enregistrement idempotent et décision proposée', async () => {
    const f = TestBed.createComponent(ControleSaisieComponent);
    f.componentRef.setInput('id', 'i1');
    f.detectChanges();
    (await req(http, '/api/inspections/i1')).flush({ id: 'i1', lotId: 'l1', controlPlanId: 'cp1', point: 'RECEPTION', sampleSize: 2, ac: 0, re: 1, results: [] });
    (await req(http, '/api/control-plans')).flush([PLAN]);
    await settle(f);
    const el: HTMLElement = f.nativeElement;
    const input = el.querySelector('input[aria-label="Mesures Diamètre"]') as HTMLInputElement;
    input.value = '12 12,05';
    input.dispatchEvent(new Event('input'));
    await settle(f);
    expect(el.textContent).toContain('Conforme');
    (el.querySelector('form[aria-label="Résultats du contrôle"] button[type=submit]') as HTMLButtonElement).click();
    const put = await req(http, '/api/inspections/i1/results');
    expect(put.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(put.request.body[0]).toEqual({ characteristic: 'D', values: [12, 12.05], ok: true, instrument: null });
    put.flush({});
    await settle(f);
    expect(el.textContent).toContain('Décision proposée : Libérer');
    (el.querySelector('form[aria-label="Décision"] button') as HTMLButtonElement).click();
    const dec = await req(http, '/api/inspections/i1/decision');
    expect(dec.request.body).toEqual({ decision: 'LIBERE', comment: '' });
    dec.flush({});
  });

  it('carte de contrôle : trace X̄ et R et marque les points hors limites', async () => {
    const f = TestBed.createComponent(CarteControleComponent);
    f.detectChanges();
    (await req(http, '/api/control-plans')).flush([PLAN]);
    const spc = await req(http, (r) => r.url === '/api/inspections/spc');
    expect(spc.request.params.get('characteristic')).toBe('D');
    spc.flush({ points: [{ mean: 12 , range: 0.1 }, { mean: 12.2, range: 0.1 }], limits: { xbar: { lcl: 11.9, cl: 12, ucl: 12.1 }, r: { lcl: 0, cl: 0.1, ucl: 0.3 } },
      cp: 1.4, cpk: 1.1, alerts: [] });
    await settle(f);
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelectorAll('svg').length).toBe(2);
    expect(el.querySelectorAll('circle.bad').length).toBe(1);
    expect(el.textContent).toContain('UCL 12,1');
  });
});
