import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { BrandingService, validateLogo } from '../../core/branding.service';
import { THEMES, Theme } from '../../core/models';

const THEME_LABELS: Record<Theme, string> = { clair: 'Clair', sombre: 'Sombre', terrain: 'Terrain (haute lisibilité)' };
export const PRESET_COLORS = [
  { hex: '#c2410c', label: 'Orange sécurité' },
  { hex: '#0b6e99', label: 'Bleu acier' },
  { hex: '#2f7d32', label: 'Vert process' },
  { hex: '#b42318', label: 'Rouge signal' },
  { hex: '#5b6670', label: 'Graphite' },
];

/** Paramétrage → Apparence (ADMIN). Les changements sont appliqués en direct puis enregistrés. */
@Component({
  selector: 'eq-apparence',
  standalone: true,
  templateUrl: './apparence.component.html',
  styleUrl: './apparence.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApparenceComponent implements OnDestroy {
  protected readonly branding = inject(BrandingService);
  protected readonly auth = inject(AuthService);
  protected readonly themes = THEMES.map((id) => ({ id, label: THEME_LABELS[id] }));
  protected readonly colors = PRESET_COLORS;
  protected readonly message = signal<{ kind: 'ok' | 'err'; text: string } | null>(null);
  protected readonly busy = signal(false);
  protected readonly dirty = computed(() => {
    const c = this.branding.current();
    const s = this.branding.saved();
    return c.primaryColor !== s.primaryColor || c.theme !== s.theme;
  });
  protected readonly today = new Intl.DateTimeFormat('fr-FR').format(new Date());

  protected setTheme(theme: Theme): void {
    this.branding.preview({ theme });
  }

  protected setColor(primaryColor: string): void {
    this.branding.preview({ primaryColor });
  }

  protected async save(): Promise<void> {
    const { primaryColor, theme } = this.branding.current();
    await this.run(() => this.branding.save(primaryColor, theme), 'Apparence enregistrée.');
  }

  protected cancel(): void {
    this.branding.revert();
    this.message.set(null);
  }

  protected async onLogo(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    const err = validateLogo(file);
    if (err) {
      this.message.set({ kind: 'err', text: err });
      return;
    }
    await this.run(() => this.branding.uploadLogo(file), 'Logo enregistré.');
  }

  private async run(op: () => Promise<unknown>, ok: string): Promise<void> {
    this.busy.set(true);
    try {
      await op();
      this.message.set({ kind: 'ok', text: ok });
    } catch {
      this.message.set({ kind: 'err', text: "L'enregistrement a échoué. Réessayez." });
    } finally {
      this.busy.set(false);
    }
  }

  /** Quitter l'écran sans enregistrer annule l'aperçu. */
  ngOnDestroy(): void {
    this.branding.revert();
  }
}
