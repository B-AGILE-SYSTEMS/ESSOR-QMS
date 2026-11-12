import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { APP_INITIALIZER, ApplicationConfig, inject, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { AuthService } from './core/auth.service';
import { BrandingService } from './core/branding.service';
import { unauthorizedInterceptor } from './core/unauthorized.interceptor';

/** Démarrage : session (/api/me, 401 → connexion) puis apparence du tenant. */
export function bootstrapSession(): () => Promise<void> {
  const auth = inject(AuthService);
  const branding = inject(BrandingService);
  return async () => {
    const me = await auth.load();
    if (!me) {
      // Redirection vers la connexion en cours : on bloque le rendu.
      return new Promise<void>(() => undefined);
    }
    try {
      await branding.load();
    } catch {
      // Apparence par défaut si l'API est indisponible.
    }
  };
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([unauthorizedInterceptor]),
    ),
    { provide: APP_INITIALIZER, multi: true, useFactory: bootstrapSession },
  ],
};
