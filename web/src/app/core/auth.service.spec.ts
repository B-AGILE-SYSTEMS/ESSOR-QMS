import { HttpClient, provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpXsrfTokenExtractor } from '@angular/common/http';
import { AuthService, LOGIN_URL } from './auth.service';
import { PAGE_NAVIGATOR } from './navigation';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

const ME = {
  username: 'admin',
  displayName: 'Salma Bennani',
  tenant: { id: 't1', code: 'DEMO', name: 'Démo' },
  sites: [],
  roles: ['ADMIN'],
};

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let assign: jasmine.Spy;

  beforeEach(() => {
    assign = jasmine.createSpy('assign');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
          withInterceptors([unauthorizedInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: PAGE_NAVIGATOR, useValue: { assign } },
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => 'jeton-csrf' } },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('charge /api/me et expose rôles et initiales', async () => {
    const p = auth.load();
    http.expectOne('/api/me').flush(ME);
    await p;
    expect(auth.authenticated()).toBeTrue();
    expect(auth.hasRole('ADMIN')).toBeTrue();
    expect(auth.hasRole('READER')).toBeFalse();
    expect(auth.initials()).toBe('SB');
  });

  it('redirige vers la connexion BFF sur 401 de /api/me', async () => {
    const p = auth.load();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(await p).toBeNull();
    expect(assign).toHaveBeenCalledOnceWith(LOGIN_URL);
  });

  it('redirige vers la connexion sur 401 de toute autre API', () => {
    TestBed.inject(HttpClient).get('/api/tenant/branding').subscribe({ error: () => undefined });
    http.expectOne('/api/tenant/branding').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(assign).toHaveBeenCalledOnceWith(LOGIN_URL);
  });

  it('déconnecte par POST /logout (JSON + CSRF) puis suit logoutUrl', async () => {
    const p = auth.logout();
    const req = http.expectOne('/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Accept')).toBe('application/json');
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('jeton-csrf');
    req.flush({ logoutUrl: 'http://localhost/auth/realms/essor/logout' });
    await p;
    expect(assign).toHaveBeenCalledOnceWith('http://localhost/auth/realms/essor/logout');
    expect(auth.authenticated()).toBeFalse();
  });
});
