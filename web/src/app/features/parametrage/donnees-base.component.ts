import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/api-error';
import { formatDateTime } from '../../core/labels';
import { ImportReport, WebhookConfig, WebhookDelivery } from '../../core/m3b.models';
import { MasterDataService } from '../../core/m3b.service';

const IMPORT_EXT = /\.(xlsx|csv)$/i;
type Kind = 'articles' | 'partners';

/** Paramétrage → Données de base & connecteurs : import articles/partenaires (simulation puis import), webhook WMS. */
@Component({
  selector: 'eq-donnees-base',
  imports: [FormsModule],
  template: `
    <div class="ph"><div><span class="ref">Paramétrage</span><h2>Données de base &amp; connecteurs</h2></div></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="grid">
      <form class="box pad" (ngSubmit)="run(true)" aria-label="Import de données de base">
        <h3 class="t">Importer (xlsx ou csv)</h3>
        <label class="field"><span>Données</span><select name="kind" [(ngModel)]="kind" (ngModelChange)="reset()">
          <option value="articles">Articles (code;designation;famille;unite;suivi;dlc)</option>
          <option value="partners">Partenaires (code;nom;type)</option></select></label>
        <label class="field"><span>Fichier</span><input type="file" accept=".xlsx,.csv" aria-label="Fichier de données de base" (change)="pick($event)" /></label>
        <div class="acts"><button type="submit" class="btn" [disabled]="busy() || !file()">Simuler l'import</button>
          <button type="button" class="btn pri" [disabled]="busy() || !simulated()" (click)="run(false)">Importer</button></div>
        @if (report(); as r) {
          <div class="rep" role="status"><p><b>{{ dry() ? 'Simulation' : 'Import' }}</b> : {{ r.created }} créé(s), {{ r.updated }} mis à jour,
            {{ r.errors.length }} erreur(s).</p>
            @if (r.errors.length) { <table><thead><tr><th>Ligne</th><th>Erreur</th></tr></thead><tbody>
              @for (e of r.errors; track $index) { <tr><td class="mono">{{ e.line }}</td><td>{{ e.message }}</td></tr> }</tbody></table> }
          </div>
        }
      </form>

      <form class="box pad" (ngSubmit)="saveHook()" aria-label="Connecteur webhook">
        <h3 class="t">Connecteur webhook (WMS) — événement lot.status_changed</h3>
        <label class="field"><span>URL</span><input name="url" type="url" [(ngModel)]="hook.url" placeholder="http://wms.local/hooks/qualite" /></label>
        <label class="field"><span>Secret HMAC</span><input name="sec" type="password" autocomplete="new-password" [(ngModel)]="secret"
          [placeholder]="hook.secretSet ? 'Secret enregistré — laisser vide pour le conserver' : ''" /></label>
        <label class="chk"><input name="en" type="checkbox" [(ngModel)]="hook.enabled" /> Actif</label>
        <button type="submit" class="btn pri" [disabled]="busy()">Enregistrer le connecteur</button>
        @if (hookOk()) { <p class="note" role="status">Connecteur enregistré.</p> }
        <h3 class="t sec">Journal des envois</h3>
        <div class="tw"><table aria-label="Journal des envois"><thead><tr><th>Date</th><th>Événement</th><th>Résultat</th></tr></thead><tbody>
          @for (d of deliveries(); track $index) {
            <tr><td class="mono">{{ dt(d.at) }}</td><td class="mono">{{ d.event ?? '—' }}</td>
              <td><span class="st" [class]="d.ok === false ? 'crit' : 'ok'"><i></i>{{ d.status ?? (d.ok === false ? 'Échec' : 'OK') }}</span>
                @if (d.error) { <span class="note"> {{ d.error }}</span> }</td></tr>
          } @empty { <tr><td colspan="3" class="note">Aucun envoi.</td></tr> }</tbody></table></div>
      </form>
    </div>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; align-items: start; }
    .t { font-size: 14px; margin: 0 0 10px; } .acts { display: flex; gap: 10px; } .rep { margin-top: 12px; }
    .chk { display: flex; gap: 8px; margin-bottom: 14px; }
    @media (max-width: 860px) { .grid { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DonneesBaseComponent {
  private readonly api = inject(MasterDataService);
  protected kind: Kind = 'articles';
  protected hook: WebhookConfig = { url: '', enabled: true };
  protected secret = '';
  protected readonly file = signal<File | null>(null);
  protected readonly report = signal<ImportReport | null>(null);
  protected readonly dry = signal(true);
  protected readonly simulated = signal(false);
  protected readonly busy = signal(false);
  protected readonly hookOk = signal(false);
  protected readonly deliveries = signal<WebhookDelivery[]>([]);
  protected readonly dt = formatDateTime;
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.loadHook();
  }

  private async loadHook(): Promise<void> {
    try {
      const h = await this.api.webhook();
      this.hook = { url: h.url ?? '', enabled: h.enabled ?? false, secretSet: h.secretSet ?? false };
      this.hookOk.set(false);
      this.deliveries.set(await this.api.deliveries());
    } catch {
      // Aucun connecteur configuré : formulaire vierge.
    }
  }

  protected reset(): void {
    this.report.set(null);
    this.simulated.set(false);
  }

  protected pick(ev: Event): void {
    const f = (ev.target as HTMLInputElement).files?.[0] ?? null;
    this.reset();
    if (f && !IMPORT_EXT.test(f.name)) {
      this.error.set('Format refusé : seuls les fichiers xlsx et csv sont acceptés.');
      this.file.set(null);
      return;
    }
    this.error.set(null);
    this.file.set(f);
  }

  async run(dryRun: boolean): Promise<void> {
    const f = this.file();
    if (!f) return;
    this.busy.set(true);
    try {
      const r = await this.api.import(this.kind, f, dryRun);
      this.report.set(r);
      this.dry.set(dryRun);
      this.simulated.set(dryRun && r.errors.length === 0);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "L'import a échoué."));
    } finally {
      this.busy.set(false);
    }
  }

  async saveHook(): Promise<void> {
    this.busy.set(true);
    try {
      const body: WebhookConfig = { url: this.hook.url.trim(), enabled: this.hook.enabled, ...(this.secret ? { secret: this.secret } : {}) };
      const h = await this.api.saveWebhook(body);
      this.hook = { url: h.url ?? body.url, enabled: h.enabled ?? body.enabled, secretSet: h.secretSet ?? (!!this.secret || this.hook.secretSet) };
      this.secret = '';
      this.hookOk.set(true);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "Le connecteur n'a pas pu être enregistré."));
    } finally {
      this.busy.set(false);
    }
  }
}
