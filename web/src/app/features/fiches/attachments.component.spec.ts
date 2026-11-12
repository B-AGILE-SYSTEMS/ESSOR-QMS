import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Attachment } from '../../core/api.models';
import { AttachmentsComponent, checkFile, formatSize, isImage } from './attachments.component';
import { INCIDENT_VIEW, dataValue, fromMobile } from './record-type-view';

const PHOTO: Attachment = {
  id: 'a1', fileName: 'fuite.jpg', contentType: 'image/jpeg', size: 245_000, createdAt: '2026-11-12T08:00:00Z', createdByName: 'K. Tazi',
};
const PDF: Attachment = { ...PHOTO, id: 'a2', fileName: 'rapport.pdf', contentType: 'application/pdf', size: 2_400_000 };

const file = (name: string, type: string, size = 10): File => new File([new Uint8Array(size)], name, { type });

describe('pièces jointes — utilitaires', () => {
  it('reconnaît les images et formate les tailles', () => {
    expect(isImage(PHOTO)).toBeTrue();
    expect(isImage(PDF)).toBeFalse();
    expect(formatSize(512)).toBe('512 o');
    expect(formatSize(245_000)).toBe('239 Ko');
    expect(formatSize(2_400_000)).toBe('2,3 Mo');
  });

  it('refuse les types non admis et les fichiers de plus de 10 Mo', () => {
    expect(checkFile(file('a.png', 'image/png'))).toBeNull();
    expect(checkFile(file('a.exe', 'application/x-msdownload'))).toContain('JPEG, PNG et PDF');
    expect(checkFile(file('gros.pdf', 'application/pdf', 10 * 1024 * 1024 + 1))).toContain('10 Mo');
  });

  it('lit les champs data et le badge mobile', () => {
    expect(dataValue('blesses', true)).toBe('Oui');
    expect(dataValue('blesses', false)).toBe('Non');
    expect(dataValue('categorie', 'presqu_accident')).toBe("Presqu'accident");
    expect(dataValue('temoins', ['A', 'B'])).toBe('A, B');
    expect(dataValue('lieu', null)).toBe('—');
    expect(dataValue('x', { a: 1 })).toBe('{"a":1}');
    expect(fromMobile({ clientId: '0b9c…' })).toBeTrue();
    expect(fromMobile({ clientId: null })).toBeFalse();
    expect(INCIDENT_VIEW.code).toBe('INCIDENT');
  });
});

describe('AttachmentsComponent', () => {
  let fixture: ComponentFixture<AttachmentsComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [AttachmentsComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AttachmentsComponent);
    fixture.componentRef.setInput('recordId', 'r1');
    fixture.detectChanges();
    http.expectOne('/api/records/r1/attachments').flush([PHOTO, PDF]);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('affiche une vignette pour les images et un lien de téléchargement pour chaque pièce', () => {
    const img = el().querySelector<HTMLImageElement>('.thumb img')!;
    expect(img.getAttribute('src')).toBe('/api/attachments/a1');
    const dl = Array.from(el().querySelectorAll<HTMLAnchorElement>('a[download]')).map((a) => a.getAttribute('href'));
    expect(dl).toEqual(['/api/attachments/a1', '/api/attachments/a2']);
    expect(el().textContent).toContain('rapport.pdf');
  });

  it("ouvre l'aperçu d'une image puis le ferme", () => {
    el().querySelector<HTMLButtonElement>('.thumb')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[role=dialog]')).not.toBeNull();
    Array.from(el().querySelectorAll<HTMLButtonElement>('[role=dialog] button')).find((b) => b.textContent === 'Fermer')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[role=dialog]')).toBeNull();
  });

  it('envoie le fichier en multipart avec une Idempotency-Key UUID et l’ajoute à la liste', async () => {
    const p = fixture.componentInstance.upload([file('photo.png', 'image/png'), file('virus.exe', 'application/octet-stream')]);
    const req = http.expectOne('/api/records/r1/attachments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    const body = req.request.body as FormData;
    expect((body.get('file') as File).name).toBe('photo.png');
    req.flush({ ...PHOTO, id: 'a3', fileName: 'photo.png', contentType: 'image/png' });
    await p;
    fixture.detectChanges();
    expect(el().textContent).toContain('photo.png');
    expect(el().querySelector('[role=alert]')!.textContent).toContain('virus.exe');
  });

  it("affiche le message du serveur en cas d'échec", async () => {
    const p = fixture.componentInstance.upload([file('photo.png', 'image/png')]);
    http.expectOne('/api/records/r1/attachments')
      .flush({ error: 'type_refuse', message: 'Contenu du fichier non reconnu.' }, { status: 415, statusText: 'Unsupported' });
    await p;
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')!.textContent).toContain('Contenu du fichier non reconnu.');
  });
});
