import { provideHttpClient, withXhr } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { errorMessage } from './api-error';
import {
  RecordsService,
  attachmentUrl,
  newIdempotencyKey,
} from './records.service';

describe('RecordsService', () => {
  let svc: RecordsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    svc = TestBed.inject(RecordsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('liste avec les filtres renseignés seulement', async () => {
    const p = svc.list({
      type: 'NC',
      status: 'ANALYSE',
      severity: '',
      q: 'fuite',
      page: 1,
      size: 20,
    });
    const req = http.expectOne((r) => r.url === '/api/records');
    expect(req.request.params.get('type')).toBe('NC');
    expect(req.request.params.get('status')).toBe('ANALYSE');
    expect(req.request.params.has('severity')).toBeFalse();
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ items: [], total: 0, page: 1, size: 20 });
    expect((await p).total).toBe(0);
  });

  it('poste une transition avec son commentaire', async () => {
    const p = svc.transition('r1', 'qualifier', 'ok');
    const req = http.expectOne('/api/records/r1/transitions/qualifier');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ comment: 'ok' });
    req.flush({ id: 'r1' });
    await p;
  });

  it('ajoute une action à la fiche parente', async () => {
    const body = {
      actionType: 'curative' as const,
      title: 'Tri',
      ownerId: 'u1',
      dueDate: '2026-11-20',
    };
    const p = svc.addAction('r1', body);
    const req = http.expectOne('/api/records/r1/actions');
    expect(req.request.body).toEqual(body);
    req.flush({ id: 'a1' });
    await p;
  });

  it('modifie par PATCH et enregistre le workflow par PUT', async () => {
    const p1 = svc.patch('r1', { data: { causeRacine: 'x' } });
    const r1 = http.expectOne('/api/records/r1');
    expect(r1.request.method).toBe('PATCH');
    r1.flush({});
    const p2 = svc.saveWorkflow('NC', { initial: 'A' });
    const r2 = http.expectOne('/api/record-types/NC/workflow');
    expect(r2.request.method).toBe('PUT');
    r2.flush({});
    await Promise.all([p1, p2]);
  });

  it("restitue le message français d'une erreur 409", () => {
    const e = new HttpErrorResponse({
      status: 409,
      error: {
        error: 'transition_impossible',
        message: 'Manque : cause racine',
      },
    });
    expect(errorMessage(e)).toBe('Manque : cause racine');
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain(
      'injoignable',
    );
  });
});

describe('RecordsService — pièces jointes', () => {
  it('liste les pièces jointes et génère des clés UUID v4 distinctes', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    const svc = TestBed.inject(RecordsService);
    const http = TestBed.inject(HttpTestingController);
    const p = svc.attachments('r 1');
    http.expectOne('/api/records/r%201/attachments').flush([]);
    expect(await p).toEqual([]);
    expect(attachmentUrl('a/1')).toBe('/api/attachments/a%2F1');
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
    const fp = svc.addAttachment(
      'r1',
      new File(['x'], 'x.png', { type: 'image/png' }),
      'k-1',
    );
    const req = http.expectOne('/api/records/r1/attachments');
    expect(req.request.headers.get('Idempotency-Key')).toBe('k-1');
    req.flush({ id: 'a1' });
    await fp;
    http.verify();
  });
});
