import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { refLabel } from '../../core/m3b.models';
import {
  CRITICITY_LABELS, MatrixCell, NewRisk, RISK_TYPES, Risk, RiskMatrix, RiskScale, criticityOf, matrixGrid, riskScore, riskTypeLabel,
} from '../../core/m4.models';
import { RisksService, duerPdfUrl } from '../../core/m4.service';

/** Pilotage → Risques : matrice interactive (clic sur une case → liste), registre, création, DUER PDF. */
@Component({
  selector: 'eq-risques',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Pilotage</span><h2>Risques &amp; opportunités</h2></div>
      <div class="acts"><a class="btn" [href]="duer()" download>Document unique (DUER) PDF</a>
        <button type="button" class="btn pri" (click)="showForm.set(!showForm())">+ Nouveau risque</button></div></div>
    <div class="flt">
      <label class="field"><span>Site</span><select name="site" [(ngModel)]="siteId" (ngModelChange)="load()">
        <option [ngValue]="null">Tous</option>@for (s of sites(); track s.id) { <option [ngValue]="s.id">{{ s.name ?? s.code }}</option> }</select></label>
      <label class="field"><span>Type</span><select name="type" [(ngModel)]="type" (ngModelChange)="load()">
        <option [ngValue]="null">Tous</option>@for (t of types; track t.code) { <option [ngValue]="t.code">{{ t.label }}</option> }</select></label>
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }

    @if (showForm() && scale(); as sc) {
      <form class="box pad frm" (ngSubmit)="create()" aria-label="Nouveau risque">
        <div class="row">
          <label class="field"><span>Type de risque</span><select name="nt" [(ngModel)]="draft.type">@for (t of types; track t.code) { <option [value]="t.code">{{ t.label }}</option> }</select></label>
          <label class="field"><span>Site du risque</span><select name="ns" [(ngModel)]="draft.siteId" required>@for (s of sites(); track s.id) { <option [ngValue]="s.id">{{ s.name ?? s.code }}</option> }</select></label>
          <label class="field"><span>Unité de travail / processus</span><input name="nu" [(ngModel)]="draft.unit" /></label>
          <label class="field"><span>Danger ou aspect</span><input name="nh" [(ngModel)]="draft.hazard" required /></label>
        </div>
        <label class="field"><span>Situation</span><input name="nsit" [(ngModel)]="draft.situation" /></label>
        <label class="field"><span>Mesures de maîtrise existantes</span><input name="nm" [(ngModel)]="draft.measures" /></label>
        <div class="row">
          @for (d of sc.dimensions; track d.code) {
            <label class="field"><span>{{ d.label }} (brute)</span><select [name]="'g-' + d.code" [(ngModel)]="draft.gross[d.code]">
              <option [ngValue]="null">—</option>@for (l of d.levels; track l.value) { <option [ngValue]="l.value">{{ l.value }} — {{ l.label }}</option> }</select></label>
          }
        </div>
        <p class="note">Cotation brute : <b class="mono">{{ previewNow()?.score ?? '—' }}</b>
          @if (previewNow()?.t; as t) { <span class="crit-chip" [style.background]="t.color">{{ t.label }}</span> }</p>
        <button type="submit" class="btn pri" [disabled]="busy() || !draft.hazard || !draft.siteId">Enregistrer le risque</button>
      </form>
    }

    <div class="two">
      <div class="box pad">
        <h3>Matrice {{ dimLabel(matrix()?.yDim) }} × {{ dimLabel(matrix()?.xDim) }}</h3>
        <div class="mx" role="grid" aria-label="Matrice des risques">
          @for (row of grid(); track $index) {
            <div class="mrow" role="row"><span class="ylab mono">{{ row[0]?.y }}</span>
              @for (c of row; track c.x) {
                <button type="button" role="gridcell" class="cell" [style.background]="c.color" [class.sel]="sel() === c"
                  [attr.aria-label]="cellLabel(c)" [attr.data-level]="c.level" (click)="sel.set(c)">{{ c.count || '' }}</button>
              }</div>
          }
          <div class="mrow"><span class="ylab"></span>@for (c of grid()[0] ?? []; track c.x) { <span class="xlab mono">{{ c.x }}</span> }</div>
        </div>
        <p class="note">Lignes : {{ dimLabel(matrix()?.yDim) }} · colonnes : {{ dimLabel(matrix()?.xDim) }}</p>
        <div class="legend">@for (t of scale()?.thresholds ?? []; track t.level) { <span class="crit-chip" [style.background]="t.color">{{ t.label }}</span> }</div>
      </div>
      <div class="box pad" aria-live="polite">
        @if (sel(); as c) {
          <h3>Case {{ c.y }} × {{ c.x }} — {{ critLabel(c.level) }} · {{ c.count }} risque(s)</h3>
          <ul class="lst" aria-label="Risques de la case">@for (r of c.risks; track r.id) {
            <li><a class="lnk" [routerLink]="'/risques/' + r.id">{{ r.reference ?? '' }} {{ r.hazard }}</a><span class="note"> · {{ typeLabel(r.type) }} · {{ ref(r.site) }}</span></li>
          } @empty { <li class="note">Aucun risque dans cette case.</li> }</ul>
        } @else { <p class="note">Cliquez une case de la matrice pour voir ses risques.</p> }
      </div>
    </div>

    <div class="box tw sec"><table>
      <thead><tr><th>Réf.</th><th>Type</th><th>Danger / aspect</th><th>Site</th><th>Unité</th><th class="r">Brute</th><th class="r">Résiduelle</th><th>Réévaluation</th></tr></thead>
      <tbody>@for (r of risks(); track r.id) {
        <tr class="go" (click)="open(r)"><td><a class="lnk mono" [routerLink]="'/risques/' + r.id">{{ r.reference ?? '—' }}</a></td><td>{{ typeLabel(r.type) }}</td>
          <td>{{ r.hazard }}</td><td>{{ ref(r.site) }}</td><td>{{ r.unit ?? '—' }}</td>
          <td class="r mono">{{ r.gross.score ?? '—' }} {{ critLabel(r.gross.level) }}</td><td class="r mono">{{ r.residual?.score ?? '—' }} {{ critLabel(r.residual?.level) }}</td>
          <td class="mono">{{ r.reviewDate ?? '—' }}</td></tr>
      } @empty { <tr><td colspan="8" class="note">Aucun risque.</td></tr> }</tbody></table></div>
  `,
  styles: `
    .acts { display: flex; gap: 10px; } h3 { margin: 0 0 12px; font-size: 15px; }
    .flt { display: flex; gap: 12px; .field { min-width: 220px; } }
    .frm { margin-bottom: 18px; } .row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0 12px; }
    .mx { display: inline-flex; flex-direction: column; gap: 4px; }
    .mrow { display: flex; gap: 4px; align-items: center; }
    .ylab, .xlab { width: 56px; text-align: center; font-size: 12px; color: var(--ink2); } .ylab { width: 24px; }
    .cell { width: 56px; height: 44px; border: var(--bw) solid var(--line2); font: 600 16px var(--mono); color: #111; cursor: pointer; }
    .cell.sel { outline: 3px solid var(--ink); outline-offset: 1px; }
    .legend { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
    .crit-chip { display: inline-block; padding: 2px 8px; font-size: 12px; color: #111; border: var(--bw) solid var(--line2); }
    .lst { margin: 0; padding-left: 18px; li { margin-bottom: 6px; } }
    @media (max-width: 860px) { .row { grid-template-columns: 1fr; } .flt { flex-wrap: wrap; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RisquesComponent {
  private readonly api = inject(RisksService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly types = RISK_TYPES;
  protected readonly ref = refLabel;
  protected readonly typeLabel = riskTypeLabel;
  protected readonly sites = computed(() => this.auth.me()?.sites ?? []);
  protected siteId: string | null = null;
  protected type: string | null = null;
  protected readonly risks = signal<Risk[]>([]);
  protected readonly matrix = signal<RiskMatrix | null>(null);
  protected readonly scale = signal<RiskScale | null>(null);
  protected readonly sel = signal<MatrixCell | null>(null);
  protected readonly showForm = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly grid = computed(() => (this.matrix() ? matrixGrid(this.matrix()!, this.scale()) : []));
  protected draft: NewRisk = this.blank();

  constructor() {
    void this.init();
  }

  private blank(): NewRisk {
    return { type: 'sst', siteId: this.auth.me()?.sites?.[0]?.id ?? null, unit: '', hazard: '', situation: '', measures: '', gross: {}, residual: {}, reviewDate: null };
  }

  private async init(): Promise<void> {
    try {
      const scales = await this.api.scales();
      this.scale.set(scales[0] ?? null);
    } catch (e) {
      this.error.set(errorMessage(e, "Échelle de cotation indisponible."));
    }
    await this.load();
  }

  async load(): Promise<void> {
    try {
      const [risks, matrix] = await Promise.all([this.api.list({ siteId: this.siteId, type: this.type }), this.api.matrix(this.siteId, this.type)]);
      this.risks.set(risks);
      this.matrix.set(matrix);
      this.sel.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger la cartographie des risques.'));
    }
  }

  protected previewNow(): { score: number | null; t: ReturnType<typeof criticityOf> } | null {
    const sc = this.scale();
    if (!sc) return null;
    const score = riskScore(sc, this.draft.gross);
    return { score, t: criticityOf(sc, score) };
  }

  protected dimLabel(code: string | null | undefined): string {
    return this.scale()?.dimensions.find((d) => d.code === code)?.label ?? code ?? '';
  }
  protected critLabel(l: string | null | undefined): string {
    if (!l) return '';
    return this.scale()?.thresholds.find((t) => t.level === l)?.label ?? CRITICITY_LABELS[l] ?? l;
  }
  protected cellLabel(c: MatrixCell): string {
    return `${this.dimLabel(this.matrix()?.yDim)} ${c.y}, ${this.dimLabel(this.matrix()?.xDim)} ${c.x} : ${c.count} risque(s)${c.level ? `, ${this.critLabel(c.level)}` : ''}`;
  }
  protected duer(): string { return duerPdfUrl(this.siteId); }

  async create(): Promise<void> {
    this.busy.set(true);
    try {
      const r = await this.api.create(this.draft);
      await this.router.navigateByUrl(`/risques/${r.id}`);
    } catch (e) {
      this.error.set(errorMessage(e, "Le risque n'a pas pu être enregistré."));
    } finally {
      this.busy.set(false);
    }
  }
  protected open(r: Risk): void { void this.router.navigateByUrl(`/risques/${r.id}`); }
}
