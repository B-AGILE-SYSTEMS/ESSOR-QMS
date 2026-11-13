
import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, DOCUMENT } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Branding, THEMES, Theme } from './models';

export const DEFAULT_BRANDING: Branding = { logoUrl: null, primaryColor: '#c2410c', theme: 'clair' };
export const LOGO_TYPES = ['image/png', 'image/svg+xml', 'image/jpeg'];
export const LOGO_MAX_BYTES = 512 * 1024;
const HEX = /^#[0-9a-f]{6}$/i;

/** Encre lisible (blanc ou noir) sur la couleur principale, au meilleur contraste WCAG. */
export function inkFor(hex: string): '#ffffff' | '#000000' {
  const n = parseInt(hex.slice(1), 16);
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  const l = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
  return 1.05 / (l + 0.05) >= (l + 0.05) / 0.05 ? '#ffffff' : '#000000';
}

export function validateLogo(file: File): string | null {
  if (!LOGO_TYPES.includes(file.type)) return 'Format refusé : PNG, SVG ou JPEG.';
  if (file.size > LOGO_MAX_BYTES) return 'Fichier trop volumineux (512 Ko maximum).';
  return null;
}

/** Apparence du tenant : chargée depuis l'API et appliquée en direct au document. */
@Injectable({ providedIn: 'root' })
export class BrandingService {
  private readonly http = inject(HttpClient);
  private readonly doc = inject(DOCUMENT);

  /** Valeur appliquée à l'écran (peut être un aperçu non encore enregistré). */
  readonly current = signal<Branding>(DEFAULT_BRANDING);
  /** Dernière valeur enregistrée côté serveur. */
  readonly saved = signal<Branding>(DEFAULT_BRANDING);
  private readonly logoVersion = signal(0);
  readonly logoSrc = computed(() => {
    const url = this.current().logoUrl;
    return url ? `${url}?v=${this.logoVersion()}` : null;
  });

  constructor() {
    effect(() => this.apply(this.current()));
  }

  async load(): Promise<Branding> {
    const b = normalize(await firstValueFrom(this.http.get<Branding>('/api/tenant/branding')));
    this.saved.set(b);
    this.current.set(b);
    return b;
  }

  preview(patch: Partial<Branding>): void {
    this.current.update((b) => normalize({ ...b, ...patch }));
  }

  revert(): void {
    this.current.set(this.saved());
  }

  async save(primaryColor: string, theme: Theme): Promise<Branding> {
    const b = normalize(
      await firstValueFrom(this.http.put<Branding>('/api/tenant/branding', { primaryColor, theme })),
    );
    this.saved.set(b);
    this.current.set(b);
    return b;
  }

  async uploadLogo(file: File): Promise<Branding> {
    const form = new FormData();
    form.append('file', file);
    const b = normalize(await firstValueFrom(this.http.post<Branding>('/api/tenant/branding/logo', form)));
    this.logoVersion.update((v) => v + 1);
    this.saved.update((s) => ({ ...s, logoUrl: b.logoUrl }));
    this.current.update((c) => ({ ...c, logoUrl: b.logoUrl }));
    return b;
  }

  /** Applique synchroniquement (utile au démarrage, avant le premier rendu). */
  apply(b: Branding): void {
    const root = this.doc.documentElement;
    root.dataset['skin'] = b.theme;
    root.style.setProperty('--acc', b.primaryColor);
    root.style.setProperty('--acc-ink', inkFor(b.primaryColor));
    root.style.colorScheme = b.theme === 'sombre' ? 'dark' : 'light';
  }
}

export function normalize(b: Partial<Branding> | null | undefined): Branding {
  const theme = b?.theme;
  return {
    logoUrl: b?.logoUrl ?? null,
    primaryColor: b?.primaryColor && HEX.test(b.primaryColor) ? b.primaryColor : DEFAULT_BRANDING.primaryColor,
    theme: theme && (THEMES as readonly string[]).includes(theme) ? theme : DEFAULT_BRANDING.theme,
  };
}
