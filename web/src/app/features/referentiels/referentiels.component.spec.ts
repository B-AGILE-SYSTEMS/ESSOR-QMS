import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { QmsRecord } from '../../core/api.models';
import { AuthService } from '../../core/auth.service';
import { DocAcksComponent } from '../documents/doc-acks.component';
import { DocVersionsComponent } from '../documents/doc-versions.component';
import { DocumentsListeComponent } from '../documents/documents-liste.component';
import { RecordRequirementsComponent } from '../fiches/record-requirements.component';
import { ReferentielsComponent } from './referentiels.component';


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

const STD = { id: 's1', code: 'ISO45001', title: 'ISO 45001', version: '2018' };
const TREE = [{ id: 'r8', code: '8', title: 'Fonctionnement', auditable: false, children: [
  { id: 'r812', code: '8.1.2', title: 'Élimination des dangers', auditable: true, children: [] }] }];
const DOC: QmsRecord = {
  id: 'd1', reference: 'DOC-1', type: 'DOCUMENT', status: 'DIFFUSE', statusLabel: 'Diffusé', title: 'Consignation', description: null,
  data: { destinataires: { roles: ['OPERATEUR'], siteIds: ['s1'] }, verifierRoles: ['QUALITE'], approverRoles: [] },
  createdBy: 'u', children: [], availableTransitions: [],
};
const V1 = { number: '1', status: 'DIFFUSE', fileName: 'pr.pdf', contentType: 'application/pdf', size: 4, createdAt: '2026-11-12T08:00:00Z', current: true };

describe('Écrans M3', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('référentiels : simulation puis import, arbre repliable, détail de l’exigence avec ses liens', async () => {
    const f = TestBed.createComponent(ReferentielsComponent);
    f.detectChanges();
    (await req(http, '/api/standards')).flush([STD]);
    await f.whenStable();
    (await req(http, '/api/standards/s1/requirements')).flush(TREE);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[role=treeitem]').length).toBe(2);
    (el.querySelector('[aria-label="Replier 8"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(el.querySelectorAll('[role=treeitem]').length).toBe(1);
    (el.querySelector('[aria-label="Déplier 8"]') as HTMLButtonElement).click();
    f.detectChanges();
    (Array.from(el.querySelectorAll<HTMLButtonElement>('.lbl')).find((b) => b.textContent!.includes('8.1.2'))!).click();
    (await req(http, '/api/requirements/r812')).flush({ ...TREE[0].children[0], standardCode: 'ISO45001', text: 'Prévenir.',
      documents: [{ id: 'd1', reference: 'PR-QHSE-07', title: 'Consignation' }], records: [{ id: 'nc-1', reference: 'NC-1', title: 'Écart', type: 'NC' }],
      findings: [{ auditId: 'a1', auditReference: 'AUD-1', result: 'ecart_majeur', comment: 'Absent' }] });
    await settle(f);
    expect(el.querySelector('.det')!.textContent).toContain('PR-QHSE-07');
    expect(el.querySelector('.det')!.textContent).toContain('AUD-1 Écart majeur');

    const input = el.querySelector('input[type=file]') as HTMLInputElement;
    const pick = (file: File) => {
      const dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('change'));
      f.detectChanges();
    };
    pick(new File(['x'], 'iso.txt'));
    expect(el.querySelector('[role=alert]')!.textContent).toContain('xlsx et csv');
    pick(new File(['code;titre'], 'iso.csv'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    (await req(http, (r) => r.url === '/api/standards/import' && r.params.get('dryRun') === 'true'))
      .flush({ created: 2, updated: 0, errors: [], applied: false, dryRun: true });
    await settle(f);
    expect(el.querySelector('.rep')!.textContent).toContain('Simulation : 2 exigence(s)');
    (Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === 'Importer')!).click();
    (await req(http, (r) => r.url === '/api/standards/import' && !r.params.has('dryRun')))
      .flush({ created: 0, updated: 0, errors: [{ line: 3, message: 'Parent inconnu' }], applied: false });
    await settle(f);
    expect(el.querySelector('[role=alert]')!.textContent).toContain('tout ou rien');
    (await req(http, '/api/standards')).flush([STD]);
    await f.whenStable();
    (await req(http, '/api/standards/s1/requirements')).flush(TREE);
    await settle(f);
  });

  it('documents : liste avec version en vigueur', async () => {
    const f = TestBed.createComponent(DocumentsListeComponent);
    f.detectChanges();
    (await req(http, (r) => r.url === '/api/documents')).flush([{ id: 'd1', reference: 'DOC-1', type: 'DOCUMENT', status: 'DIFFUSE', statusLabel: 'Diffusé',
      title: 'Consignation', data: { code: 'PR-QHSE-07', docType: 'procedure' }, currentVersion: { id: 'v', number: 3, fileName: 'a.pdf' } }]);
    await settle(f);
    const row = (f.nativeElement as HTMLElement).querySelector('tr.go')!.textContent!;
    expect(row).toContain('PR-QHSE-07');
    expect(row).toContain('Procédure');
    expect(row).toContain('3');
  });

  it('versions : visionneuse de la version en vigueur et transitions par numéro', async () => {
    const f = TestBed.createComponent(DocVersionsComponent);
    f.componentRef.setInput('docId', 'd1');
    f.detectChanges();
    await f.whenStable();
    (await req(http, '/api/documents/d1/versions')).flush([{ ...V1, number: '2', status: 'APPROBATION', current: false }, V1]);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('iframe')!.getAttribute('src')).toBe('/api/documents/d1/versions/1/file');
    (Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent === 'Approuver et diffuser')!).click();
    (await req(http, '/api/documents/d1/versions/2/transitions/approuver')).flush(null);
    await f.whenStable();
    (await req(http, '/api/documents/d1/versions')).flush([V1]);
    await settle(f);
  });

  it('accusés de lecture : l’utilisateur courant accuse lecture', async () => {
    const auth = TestBed.inject(AuthService);
    spyOn(auth, 'me').and.returnValue({ username: 'sbennani', displayName: 'Salma Bennani', tenant: { id: 't', code: 'T', name: 'T' },
      sites: [{ id: 's1', code: 'KEN', name: 'Kénitra' }], roles: [] });
    const f = TestBed.createComponent(DocAcksComponent);
    f.componentRef.setInput('record', DOC);
    f.detectChanges();
    await f.whenStable();
    (await req(http, '/api/documents/d1/versions')).flush([V1]);
    await f.whenStable();
    (await req(http, '/api/documents/d1/versions/1/acks')).flush({ read: [], pending: [{ userId: 'sbennani', displayName: 'Salma Bennani' }] });
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Kénitra');
    expect(el.textContent).toContain('QUALITE');
    (el.querySelector('button.pri') as HTMLButtonElement).click();
    (await req(http, '/api/documents/d1/versions/1/ack')).flush(null);
    await f.whenStable();
    (await req(http, '/api/documents/d1/versions/1/acks')).flush({ read: [{ userId: 'sbennani', displayName: 'Salma Bennani', at: null }], pending: [] });
    await settle(f);
    expect(el.querySelector('.bar')!.textContent).toContain('1/1');
    expect(el.querySelector('button.pri')).toBeNull();
  });

  it('exigences d’une fiche : coche et enregistre les liens', async () => {
    const f = TestBed.createComponent(RecordRequirementsComponent);
    f.componentRef.setInput('record', { ...DOC, requirements: [{ id: 'r8', code: '8', title: 'Fonctionnement' }] } as QmsRecord);
    f.detectChanges();
    (await req(http, '/api/standards')).flush([STD]);
    await f.whenStable();
    (await req(http, '/api/standards/s1/requirements')).flush(TREE);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    const boxes = el.querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    expect(boxes[0].checked).toBeTrue();
    boxes[1].click();
    f.detectChanges();
    (el.querySelector('button.pri') as HTMLButtonElement).click();
    const put = (await req(http, '/api/records/d1/requirements'));
    expect(put.request.body).toEqual(['r8', 'r812']);
    put.flush(null);
    await settle(f);
    expect(el.textContent).toContain('Liens enregistrés.');
  });
});
