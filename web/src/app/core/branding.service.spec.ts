import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BrandingService, DEFAULT_BRANDING, inkFor, normalize, validateLogo } from './branding.service';

describe('BrandingService', () => {
  let svc: BrandingService;
  let http: HttpTestingController;
  const root = document.documentElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    svc = TestBed.inject(BrandingService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it("charge l'apparence et l'applique au document", async () => {
    const p = svc.load();
    http.expectOne('/api/tenant/branding').flush({ logoUrl: null, primaryColor: '#0b6e99', theme: 'sombre' });
    await p;
    TestBed.flushEffects();
    expect(root.dataset['skin']).toBe('sombre');
    expect(root.style.getPropertyValue('--acc')).toBe('#0b6e99');
  });

  it("applique l'aperçu en direct puis l'annule", () => {
    svc.preview({ primaryColor: '#2f7d32', theme: 'terrain' });
    TestBed.flushEffects();
    expect(root.dataset['skin']).toBe('terrain');
    expect(root.style.getPropertyValue('--acc')).toBe('#2f7d32');
    svc.revert();
    expect(svc.current()).toEqual(DEFAULT_BRANDING);
  });

  it('enregistre par PUT /api/tenant/branding', async () => {
    const p = svc.save('#b42318', 'clair');
    const req = http.expectOne('/api/tenant/branding');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ primaryColor: '#b42318', theme: 'clair' });
    req.flush({ logoUrl: null, primaryColor: '#b42318', theme: 'clair' });
    await p;
    expect(svc.saved().primaryColor).toBe('#b42318');
  });

  it('envoie le logo en multipart (champ file) et rafraîchit son URL', async () => {
    const file = new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' });
    const p = svc.uploadLogo(file);
    const req = http.expectOne('/api/tenant/branding/logo');
    expect(req.request.method).toBe('POST');
    expect((req.request.body as FormData).get('file')).toBeTruthy();
    req.flush({ logoUrl: '/api/tenant/branding/logo', primaryColor: '#c2410c', theme: 'clair' });
    await p;
    expect(svc.logoSrc()).toBe('/api/tenant/branding/logo?v=1');
  });

  it('rejette les valeurs invalides venant du serveur', () => {
    expect(normalize({ primaryColor: 'red', theme: 'rose' as never })).toEqual(DEFAULT_BRANDING);
  });

  it('valide format et taille du logo', () => {
    expect(validateLogo(new File(['x'], 'a.gif', { type: 'image/gif' }))).toContain('Format');
    expect(validateLogo(new File([new Uint8Array(512 * 1024 + 1)], 'a.png', { type: 'image/png' }))).toContain('512');
    expect(validateLogo(new File(['x'], 'a.png', { type: 'image/png' }))).toBeNull();
  });

  it("choisit l'encre la plus contrastée", () => {
    expect(inkFor('#c2410c')).toBe('#ffffff');
    expect(inkFor('#ffde00')).toBe('#000000');
  });
});
