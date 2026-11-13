import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { Partner } from '../../core/m3b.models';
import { MasterDataService } from '../../core/m3b.service';
import { EvaluationGrid, PartnerEvaluation, currentQuarter } from '../../core/m4.models';
import { EvaluationsService } from '../../core/m4.service';

/** Ressources → Clients & fournisseurs : liste, accès au scorecard, lancement d'une campagne d'évaluation. */
@Component({
  selector: 'eq-partenaires',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Ressources</span><h2>Clients &amp; fournisseurs</h2></div>
      <button type="button" class="btn pri" (click)="showForm.set(!showForm())">+ Campagne d'évaluation</button></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (showForm()) {
      <form class="box pad frm" (ngSubmit)="launch()" aria-label="Campagne d'évaluation">
        <div class="row"><label class="field"><span>Grille</span><select name="g" [(ngModel)]="gridId" (ngModelChange)="gridIdSig.set($event)" required>
            @for (g of grids(); track g.id) { <option [ngValue]="g.id">{{ g.code }} — {{ g.label }}</option> }</select></label>
          <label class="field"><span>Période</span><input name="p" [(ngModel)]="period" required /></label></div>
        <fieldset><legend>Partenaires évalués</legend>
          @for (p of eligible(); track p.id) { <label class="chk"><input type="checkbox" [name]="'p-' + p.id" [ngModel]="picked().has(p.id)" (ngModelChange)="toggle(p.id)" /> {{ p.code }} — {{ p.nom }}</label> }
        </fieldset>
        <button type="submit" class="btn pri" [disabled]="busy() || !gridId || !picked().size">Créer les évaluations</button>
      </form>
    }
    @if (created().length) { <p class="note" role="status">{{ created().length }} évaluation(s) à faire créée(s) pour {{ period }}.</p> }
    <div class="chips"><button type="button" class="chip" [attr.aria-pressed]="type() === 'fournisseur'" (click)="type.set('fournisseur')">Fournisseurs</button>
      <button type="button" class="chip" [attr.aria-pressed]="type() === 'client'" (click)="type.set('client')">Clients</button></div>
    <div class="box tw sec"><table aria-label="Partenaires">
      <thead><tr><th>Code</th><th>Nom</th><th>Type</th><th></th></tr></thead>
      <tbody>@for (p of shown(); track p.id) {
        <tr><td class="mono">{{ p.code }}</td><td><a class="lnk" [routerLink]="'/partenaires/' + p.id">{{ p.nom }}</a></td><td>{{ p.type }}</td>
          <td><a class="lnk" [routerLink]="'/partenaires/' + p.id">Scorecard →</a></td></tr>
      } @empty { <tr><td colspan="4" class="note">Aucun partenaire.</td></tr> }</tbody></table></div>
  `,
  styles: `.frm { margin-bottom: 16px; } .row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; } fieldset { border: var(--bw) solid var(--line); margin: 0 0 14px; } .chk { display: block; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PartenairesComponent {
  private readonly md = inject(MasterDataService);
  private readonly api = inject(EvaluationsService);
  protected readonly partners = signal<Partner[]>([]);
  protected readonly grids = signal<EvaluationGrid[]>([]);
  protected readonly type = signal('fournisseur');
  protected readonly shown = computed(() => this.partners().filter((p) => p.type === this.type()));
  protected readonly picked = signal(new Set<string>());
  protected readonly created = signal<PartnerEvaluation[]>([]);
  protected readonly showForm = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected gridId: string | null = null;
  protected period = currentQuarter();
  protected readonly eligible = computed(() => {
    const g = this.grids().find((x) => x.id === this.gridIdSig());
    return this.partners().filter((p) => !g || p.type === g.partnerType);
  });
  protected readonly gridIdSig = signal<string | null>(null);

  constructor() {
    void this.load();
  }
  private async load(): Promise<void> {
    try {
      const [p, g] = await Promise.all([this.md.partners(), this.api.grids()]);
      this.partners.set(p);
      this.grids.set(g);
      this.gridId = g[0]?.id ?? null;
      this.gridIdSig.set(this.gridId);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les partenaires.'));
    }
  }
  protected toggle(id: string): void {
    this.picked.update((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  }
  async launch(): Promise<void> {
    this.busy.set(true);
    try {
      this.created.set(await this.api.campaign({ gridId: this.gridId!, partnerIds: [...this.picked()], period: this.period }));
      this.showForm.set(false);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "La campagne n'a pas pu être créée."));
    } finally {
      this.busy.set(false);
    }
  }
}
