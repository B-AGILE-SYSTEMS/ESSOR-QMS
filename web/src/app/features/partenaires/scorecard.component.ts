import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { Partner, fmt } from '../../core/m3b.models';
import { MasterDataService } from '../../core/m3b.service';
import { EvaluationGrid, PartnerEvaluation, Scorecard, classOf, classTone, currentQuarter, weightedScore } from '../../core/m4.models';
import { EvaluationsService } from '../../core/m4.service';
import { ActionLinksComponent } from '../risques/action-links.component';

/** Scorecard d'un partenaire : historique des notes et indicateurs automatiques ; saisie de l'évaluation (classe C → plan de progrès). */
@Component({
  selector: 'eq-scorecard',
  imports: [FormsModule, RouterLink, ActionLinksComponent],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="ph"><div><span class="ref mono">{{ partner()?.code }} · {{ partner()?.type }}</span><h2>Scorecard {{ partner()?.nom }}</h2></div>
      @if (last(); as l) { <span class="st" data-testid="partner-class" [class]="tone(l.cls)"><i></i>Classe {{ l.cls ?? '—' }} · {{ fmt(l.score, 1) }}/100</span> }</div>

    <div class="box pad"><h3>Historique</h3>
      <svg class="bars" [attr.viewBox]="'0 0 ' + width() + ' 140'" role="img" aria-label="Évolution de la note globale">
        @for (e of done(); track e.id; let k = $index) {
          <rect [attr.x]="20 + k * 90" [attr.y]="120 - (e.score ?? 0)" width="56" [attr.height]="e.score ?? 0" [attr.class]="'b ' + tone(e.cls)" />
          <text [attr.x]="48 + k * 90" [attr.y]="114 - (e.score ?? 0)" text-anchor="middle">{{ e.score }}</text>
          <text [attr.x]="48 + k * 90" y="136" text-anchor="middle">{{ e.period }}</text>
        }
      </svg>
      <div class="tw"><table aria-label="Évaluations">
        <thead><tr><th>Période</th><th class="r">Note</th><th>Classe</th><th class="r">NC liées</th><th class="r">% lots refusés</th><th class="r">Dérogations</th>
          <th class="r">Retards</th><th class="r">Réclamations</th><th>Plan de progrès</th></tr></thead>
        <tbody>@for (e of sc()?.evaluations ?? []; track e.id) {
          <tr><td class="mono">{{ e.period }}</td><td class="r mono">{{ e.score === null ? 'à faire' : fmt(e.score, 1) }}</td><td><span class="st" [class]="tone(e.cls)"><i></i>{{ e.cls ?? '—' }}</span></td>
            <td class="r mono">{{ n(e.indicators.ncCount) }}</td><td class="r mono">{{ n(e.indicators.refusedRate) }}{{ e.indicators.refusedRate === null ? '' : ' %' }}</td>
            <td class="r mono">{{ n(e.indicators.derogations) }}</td><td class="r mono">{{ n(e.indicators.lateDeliveries) }}</td><td class="r mono">{{ n(e.indicators.complaints) }}</td>
            <td><eq-action-links [actions]="e.actions" empty="—" /></td></tr>
        } @empty { <tr><td colspan="9" class="note">Aucune évaluation.</td></tr> }</tbody></table></div>
    </div>

    @if (grid(); as g) {
      <form class="box pad sec" (ngSubmit)="save()" aria-label="Évaluation du partenaire">
        <h3>Évaluer — {{ g.label }}</h3>
        <div class="row"><label class="field"><span>Période évaluée</span><input name="per" [(ngModel)]="period" required /></label></div>
        <div class="row">@for (c of g.criteria; track c.code) {
          <label class="field"><span>{{ c.label }} (poids {{ c.weight }})</span>
            <input type="number" min="0" [max]="g.scaleMax" [name]="'c-' + c.code" [attr.aria-label]="'Note ' + c.label" [(ngModel)]="scores[c.code]" /></label>
        }</div>
        <p class="note">Note des critères : <b class="mono">{{ fmt(preview(), 1) }}</b>/100 · classe prévisionnelle <b>{{ previewClass() ?? '—' }}</b>.
          Les indicateurs automatiques (NC, lots refusés, retards) sont intégrés par le serveur.</p>
        <label class="field"><span>Commentaire</span><textarea name="cm" rows="2" [(ngModel)]="comment"></textarea></label>
        <button type="submit" class="btn pri" [disabled]="busy() || preview() === null">Calculer et enregistrer</button>
      </form>
    }
    <p class="sec"><a class="lnk" routerLink="/partenaires">← Clients &amp; fournisseurs</a></p>
  `,
  styles: `
    h3 { margin: 0 0 10px; font-size: 15px; } .row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0 12px; }
    .bars { width: 100%; max-height: 160px; margin-bottom: 12px; text { font: 12px var(--mono); fill: var(--ink2); }
      .b { fill: var(--info); } .b.ok { fill: var(--ok); } .b.warn { fill: var(--warn); } .b.crit { fill: var(--crit); } }
    @media (max-width: 860px) { .row { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScorecardComponent {
  private readonly api = inject(EvaluationsService);
  private readonly md = inject(MasterDataService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly partner = signal<Partner | null>(null);
  protected readonly sc = signal<Scorecard | null>(null);
  protected readonly grid = signal<EvaluationGrid | null>(null);
  protected readonly done = computed(() => (this.sc()?.evaluations ?? []).filter((e) => e.score !== null));
  protected readonly last = computed(() => this.done().at(-1) ?? null);
  protected readonly width = computed(() => Math.max(400, 40 + this.done().length * 90));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fmt = fmt;
  protected scores: Record<string, number | null> = {};
  protected period = currentQuarter();
  protected comment = '';

  constructor() {
    void this.load();
  }
  private async load(): Promise<void> {
    try {
      const [ps, sc, grids] = await Promise.all([this.md.partners(), this.api.scorecard(this.id), this.api.grids()]);
      const p = ps.find((x) => x.id === this.id) ?? null;
      this.partner.set(p);
      this.sc.set(sc);
      const todo = sc.evaluations.find((e) => e.score === null);
      if (todo) this.period = todo.period;
      this.grid.set(grids.find((g) => g.id === todo?.gridId) ?? grids.find((g) => g.partnerType === p?.type) ?? grids[0] ?? null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Scorecard indisponible.'));
    }
  }
  protected preview(): number | null { return this.grid() ? weightedScore(this.grid()!, this.scores) : null; }
  protected previewClass(): string | null { return this.grid() ? (classOf(this.grid()!, this.preview())?.cls ?? null) : null; }
  protected tone(c: string | null): string { return classTone(c, this.grid()); }
  protected n(v: number | null): string { return v === null ? '—' : String(v).replace('.', ','); }

  async save(): Promise<void> {
    const g = this.grid()!;
    this.busy.set(true);
    try {
      const todo: PartnerEvaluation | undefined = this.sc()?.evaluations.find((e) => e.score === null && e.period === this.period);
      const scores = Object.fromEntries(g.criteria.map((c) => [c.code, Number(this.scores[c.code])]));
      await this.api.evaluate(this.id, { evaluationId: todo?.id ?? null, gridId: g.id!, period: this.period, scores, comment: this.comment });
      this.scores = {};
      this.comment = '';
      this.error.set(null);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e, "L'évaluation n'a pas pu être enregistrée."));
    } finally {
      this.busy.set(false);
    }
  }
}
