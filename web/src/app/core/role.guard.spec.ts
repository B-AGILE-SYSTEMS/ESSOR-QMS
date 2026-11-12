import { TestBed } from '@angular/core/testing';
import { CanMatchFn, Router, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { roleGuard } from './role.guard';

describe('roleGuard', () => {
  let roles: string[];

  beforeEach(() => {
    roles = [];
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { hasRole: (r: string) => roles.includes(r) } },
      ],
    });
  });

  const run = () =>
    TestBed.runInInjectionContext(() =>
      (roleGuard('ADMIN') as CanMatchFn)({}, []),
    );

  it('autorise un ADMIN', () => {
    roles = ['ADMIN'];
    expect(run()).toBeTrue();
  });

  it("refuse un non-ADMIN et renvoie vers l'accueil", () => {
    roles = ['READER'];
    const res = run();
    expect(res instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(res as UrlTree)).toBe('/mes-actions');
  });
});
