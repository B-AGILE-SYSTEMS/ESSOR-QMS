import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Garde de rôle : `canMatch: [roleGuard('ADMIN')]`. Refus → accueil. */
export function roleGuard(...roles: string[]): CanMatchFn {
  return () => {
    const auth = inject(AuthService);
    if (roles.some((r) => auth.hasRole(r))) {
      return true;
    }
    return inject(Router).createUrlTree(['/mes-actions']);
  };
}
