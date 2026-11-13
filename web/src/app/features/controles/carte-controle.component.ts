import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/api-error';
import { ControlPlan, Spc, fmt } from '../../core/m3b.models';
import { ControlPlansService, InspectionsService } from '../../core/m3b.service';
import { SpcChartComponent } from './spc-chart.component';

/** Carte de contrôle X̄/R d'une caractéristique mesurée (limites, Cp/Cpk, alertes calculés par le serveur). */
@Component({
  selector: 'eq-carte-controle',
  imports: [FormsModule, SpcChartComponent],
  template: `
    <div class="ph"><div><span class="ref">Terrain · Contrôles</span><h2>Carte de contrôle</h2></div></div>
    <div class="flt">
      <label class="field"><span>Plan de contrôle</span><select name="plan" [(ngModel)]="planId" (ngModelChange)="pickPlan()">
        @for (p of plans(); track p.id) { <option [value]="p.id">{{ p.code }} — {{ p.libelle }}</option> }</select></label>
      <label class="field"><span>Caractéristique</span><select name="car" [(ngModel)]="charCode" (ngModelChange)="load()">
        @for (c of measures(); track c.code) { <option [value]="c.code">{{ c.code }} — {{ c.libelle }}</option> }</select></label>
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (spc(); as s) {
      <div class="kpis">
        <div><span>Cp</span><b class="mono">{{ f(s.cp, 2) }}</b></div>
        <div><span>Cpk</span><b class="mono" [class.bad]="(s.cpk ?? 2) < 1.33">{{ f(s.cpk, 2) }}</b></div>
        <div><span>Sous-groupes</span><b class="mono">{{ s.points.length }}</b></div>
        <div><span>Alertes</span><b class="mono" [class.bad]="s.alerts.length">{{ s.alerts.length }}</b></div>
      </div>
      <div class="box pad sec"><h3>Moyennes (X̄)</h3>
        <eq-spc-chart title="Carte des moyennes" [points]="s.points" [limits]="s.xbar" [alerts]="s.alerts" kind="mean" [tolerances]="tol()" /></div>
      <div class="box pad sec"><h3>Étendues (R)</h3>
        <eq-spc-chart title="Carte des étendues" [points]="s.points" [limits]="s.r" kind="range" /></div>
      @if (s.alerts.length) {
        <ul class="box pad alerts sec" aria-label="Alertes">@for (a of s.alerts; track $index) {
          <li><span class="st crit"><i></i>Sous-groupe {{ a.index + 1 }}</span> {{ a.message ?? a.type }}</li> }</ul>
      }
    }
  `,
  styles: `
    .flt { display: flex; gap: 12px; .field { min-width: 280px; } }
    .kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px;
      div { background: var(--surf); border: var(--bw) solid var(--line); padding: 8px 14px; } span { display: block; font-size: 12px; color: var(--ink2); }
      b { font-size: 24px; } b.bad { color: var(--crit); } }
    .alerts { list-style: none; margin: 0; li { padding: 4px 0; } }
    @media (max-width: 860px) { .flt { flex-direction: column; } .kpis { grid-template-columns: 1fr 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarteControleComponent {
  private readonly plansApi = inject(ControlPlansService);
  private readonly api = inject(InspectionsService);
  protected readonly f = fmt;
  protected readonly plans = signal<ControlPlan[]>([]);
  protected readonly spc = signal<Spc | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly selected = signal<ControlPlan | null>(null);
  protected planId = '';
  protected charCode = '';
  protected readonly measures = computed(() => (this.selected()?.characteristics ?? []).filter((c) => c.type === 'mesure'));
  protected readonly tol = computed(() => {
    const s = this.spc();
    const c = this.measures().find((x) => x.code === this.charCode);
    return { min: s?.tolMin ?? c?.tolMin ?? null, max: s?.tolMax ?? c?.tolMax ?? null };
  });

  constructor() {
    void this.init();
  }

  private async init(): Promise<void> {
    try {
      const list = (await this.plansApi.list()).filter((p) => p.characteristics.some((c) => c.type === 'mesure'));
      this.plans.set(list);
      const enCours = list.find((p) => p.point === 'PRODUCTION_ENCOURS') ?? list[0];
      if (enCours?.id) {
        this.planId = enCours.id;
        this.pickPlan();
      }
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les plans.'));
    }
  }

  protected pickPlan(): void {
    this.selected.set(this.plans().find((p) => p.id === this.planId) ?? null);
    this.charCode = this.measures()[0]?.code ?? '';
    void this.load();
  }

  async load(): Promise<void> {
    if (!this.planId || !this.charCode) return;
    try {
      this.spc.set(await this.api.spc(this.planId, this.charCode));
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de calculer la carte de contrôle.'));
    }
  }
}
