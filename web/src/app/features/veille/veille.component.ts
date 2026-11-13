import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate } from '../../core/labels';
import { REG_DOMAINS, REG_STATUS, Regulation, SiteCompliance } from '../../core/m4.models';
import { RegulationsService } from '../../core/m4.service';

/** Pilotage → Veille réglementaire : taux de conformité par site et registre des textes. */
@Component({
  selector: 'eq-veille',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Pilotage</span><h2>Veille réglementaire</h2></div></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="kpis" aria-label="Conformité réglementaire par site">
      @for (c of compliance(); track c.siteId) {
        <div class="box pad kpi"><span class="note">{{ c.siteName }}</span><b class="mono v">{{ pct(c.rate) }}</b>
          <span class="note">{{ c.counts['conforme'] }} conforme(s) · {{ c.counts['partiel'] }} partiel(s) · {{ c.counts['non_conforme'] }} non conforme(s)</span></div>
      }
    </div>
    <div class="flt">
      <label class="field"><span>Domaine</span><select name="d" [(ngModel)]="domain" (ngModelChange)="load()"><option [ngValue]="null">Tous</option>
        @for (d of domains; track d) { <option [ngValue]="d">{{ d }}</option> }</select></label>
      <label class="field"><span>Site</span><select name="s" [(ngModel)]="siteId" (ngModelChange)="load()"><option [ngValue]="null">Tous</option>
        @for (s of sites(); track s.id) { <option [ngValue]="s.id">{{ s.name ?? s.code }}</option> }</select></label>
      <label class="field"><span>Statut</span><select name="st" [(ngModel)]="status" (ngModelChange)="load()"><option [ngValue]="null">Tous</option>
        @for (s of statuses; track s[0]) { <option [ngValue]="s[0]">{{ s[1] }}</option> }</select></label>
    </div>
    <div class="box tw"><table aria-label="Registre réglementaire">
      <thead><tr><th>Référence</th><th>Titre</th><th>Domaine</th><th>Juridiction</th><th>Application</th><th>Statut</th></tr></thead>
      <tbody>@for (r of regs(); track r.id) {
        <tr class="go" (click)="open(r)"><td><a class="lnk" [routerLink]="'/veille/' + r.id">{{ r.reference }}</a></td><td>{{ r.title }}</td>
          <td>{{ r.domain ?? '—' }}</td><td>{{ r.jurisdiction ?? '—' }}</td><td class="mono">{{ date(r.effectiveAt) }}</td><td>{{ statusLabel(r.status) }}</td></tr>
      } @empty { <tr><td colspan="6" class="note">Aucun texte.</td></tr> }</tbody></table></div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
    .kpi { display: flex; flex-direction: column; } .v { font-size: 28px; }
    .flt { display: flex; gap: 12px; .field { min-width: 200px; } }
    @media (max-width: 860px) { .kpis { grid-template-columns: 1fr; } .flt { flex-wrap: wrap; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VeilleComponent {
  private readonly api = inject(RegulationsService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly domains = REG_DOMAINS;
  protected readonly statuses = Object.entries(REG_STATUS);
  protected readonly date = formatDate;
  protected readonly sites = computed(() => this.auth.me()?.sites ?? []);
  protected domain: string | null = null;
  protected siteId: string | null = null;
  protected status: string | null = null;
  protected readonly regs = signal<Regulation[]>([]);
  protected readonly compliance = signal<SiteCompliance[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.load();
    this.api.compliance().then((c) => this.compliance.set(c), (e) => this.error.set(errorMessage(e, 'Taux de conformité indisponible.')));
  }

  async load(): Promise<void> {
    try {
      this.regs.set(await this.api.list({ domain: this.domain, siteId: this.siteId, status: this.status }));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger le registre réglementaire.'));
    }
  }
  protected pct(r: number | null): string { return r === null ? '—' : `${Math.round(r)} %`; }
  protected statusLabel(s: string): string { return REG_STATUS[s] ?? s; }
  protected open(r: Regulation): void { void this.router.navigateByUrl(`/veille/${r.id}`); }
}
