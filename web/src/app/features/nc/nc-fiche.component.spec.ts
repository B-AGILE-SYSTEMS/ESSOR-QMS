import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { QmsRecord } from '../../core/api.models';
import { NcFicheComponent } from './nc-fiche.component';

const REC: QmsRecord = {
  id: 'r1', reference: 'NC-2026-0001', type: 'NC', status: 'ANALYSE', statusLabel: 'Analyse', title: 'Fuite',
  description: '', severity: 'majeure', siteId: 's1', siteName: 'Kénitra', ownerId: 'u1', ownerName: 'K. Tazi',
  dueDate: null, data: {}, createdAt: '2026-11-12T08:00:00Z', createdBy: 'Y. Amrani', children: [],
  availableTransitions: [
    { code: 'valider_analyse', label: "Valider l'analyse", missing: ['data.causeRacine'] },
    { code: 'renvoyer_analyse', label: 'Renvoyer', missing: [] },
  ],
};

describe('NcFicheComponent', () => {
  let fixture: ComponentFixture<NcFicheComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [NcFicheComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NcFicheComponent);
    fixture.componentRef.setInput('id', 'r1');
    fixture.detectChanges();
    http.expectOne('/api/record-types/NC/workflow').flush({ initial: 'DECLAREE', states: [], transitions: [] });
    http.expectOne('/api/records/r1').flush(REC);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const button = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('.nextbar button')).find((b) => b.textContent!.includes(label))!;

  it("ouvre l'onglet Analyse et désactive la transition incomplète en listant ce qui manque", () => {
    expect(el().querySelector('[role=tab][aria-selected=true]')!.textContent).toContain('Analyse');
    expect(button("Valider l'analyse").disabled).toBeTrue();
    expect(el().querySelector('.miss')!.textContent).toContain('Cause racine');
    expect(button('Renvoyer').disabled).toBeFalse();
  });

  it('effectue la transition avec le commentaire et affiche la nouvelle étape', async () => {
    button('Renvoyer').click();
    const req = http.expectOne('/api/records/r1/transitions/renvoyer_analyse');
    expect(req.request.body).toEqual({ comment: '' });
    req.flush({ ...REC, status: 'QUALIFIEE', statusLabel: 'Qualifiée', availableTransitions: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().textContent).toContain('Qualifiée');
    expect(el().querySelector('.nextbar')).toBeNull();
  });

  it('affiche le message français du serveur sur 409', async () => {
    button('Renvoyer').click();
    http
      .expectOne('/api/records/r1/transitions/renvoyer_analyse')
      .flush({ error: 'conflit', message: 'Transition impossible : cause racine manquante' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')!.textContent).toContain('cause racine manquante');
  });
});
