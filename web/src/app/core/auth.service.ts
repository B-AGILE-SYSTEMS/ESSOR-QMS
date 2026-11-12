import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Me } from './models';
import { PAGE_NAVIGATOR } from './navigation';

export const LOGIN_URL = '/oauth2/authorization/keycloak';

/** Session BFF : le navigateur ne détient jamais de jeton, seulement le cookie SESSION. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly nav = inject(PAGE_NAVIGATOR);

  private readonly _me = signal<Me | null>(null);
  readonly me = this._me.asReadonly();
  readonly authenticated = computed(() => this._me() !== null);
  readonly roles = computed(() => new Set(this._me()?.roles ?? []));
  readonly initials = computed(() =>
    (this._me()?.displayName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p.charAt(0).toUpperCase())
      .join(''),
  );

  hasRole(role: string): boolean {
    return this.roles().has(role);
  }

  /** Charge l'utilisateur courant ; 401 → redirection vers la connexion. */
  async load(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.http.get<Me>('/api/me'));
      this._me.set(me);
      return me;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 401) {
        this.login();
        return null;
      }
      throw e;
    }
  }

  login(): void {
    this._me.set(null);
    this.nav.assign(LOGIN_URL);
  }

  /** POST /logout (jeton CSRF ajouté par HttpClient) puis redirection vers la déconnexion Keycloak. */
  async logout(): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<{ logoutUrl?: string } | null>('/logout', null, {
        headers: { Accept: 'application/json' },
      }),
    );
    this._me.set(null);
    this.nav.assign(res?.logoutUrl || '/');
  }
}
