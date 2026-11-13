import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate } from '../../core/labels';
import { EVAL_RESULTS, REG_STATUS, RegEvaluation, Regulation, evalLabel, evalTone, latestBySite } from '../../core/m4.models';
import { RegulationsService } from '../../core/m4.service';
import { ActionLinksComponent } from '../risques/action-links.component';

/** Fiche texte réglementaire : exigences applicables par site, évaluation périodique (non conforme → plan d'action M1). */
@Component({
  selector: 'eq-reglement-fiche',
  imports: [FormsModule, RouterLink, ActionLinksComponent],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (reg(); as r) {
      <div class="ph"><div><span class="ref">{{ r.reference }} · {{ r.jurisdiction ?? '' }} · {{ r.domain ?? '' }}</span><h2>{{ r.title }}</h2></div>
        <span class="badge">{{ statusLabel(r.status) }}</span></div>
      <div class="two">
        <div class="box pad"><dl class="kv">
          <dt>Publication</dt><dd class="mono">{{ date(r.publishedAt) }}</dd><dt>Application</dt><dd class="mono">{{ date(r.effectiveAt) }}</dd>
          <dt>Résumé</dt><dd>{{ r.summary ?? '—' }}</dd>
          <dt>Source</dt><dd>@if (safeUrl(r.sourceUrl); as u) { <a class="lnk" [href]="u" target="_blank" rel="noopener">{{ u }}</a> } @else { — }</dd>
        </dl>
          <h3 class="sec">Exigences applicables</h3>
          <ul class="lst">@for (q of r.requirements; track q.id) {
            <li>{{ q.text }}@for (a of q.applicability; track a.siteId) { <span class="note"> · {{ siteName(a.siteId) }} : {{ a.applicable ? 'oui' : 'non' }}{{ a.justification ? ' (' + a.justification + ')' : '' }}</span> }</li>
          } @empty { <li class="note">Aucune exigence extraite.</li> }</ul>
        </div>
        <form class="box pad" (ngSubmit)="evaluate()" aria-label="Évaluation de conformité">
          <h3>Évaluer la conformité</h3>
          <label class="field"><span>Site évalué</span><select name="site" [(ngModel)]="siteId" required>
            @for (s of sites(); track s.id) { <option [ngValue]="s.id">{{ s.name ?? s.code }}</option> }</select></label>
          <label class="field"><span>Résultat</span><select name="res" [(ngModel)]="result">
            @for (e of results; track e.code) { <option [value]="e.code">{{ e.label }}</option> }</select></label>
          <label class="field"><span>Preuve</span><input name="ev" [(ngModel)]="evidence" /></label>
          <label class="field"><span>Commentaire</span><textarea name="cm" rows="2" [(ngModel)]="comment"></textarea></label>
          @if (result === 'non_conforme') { <p class="note">Un plan d'action (fiche ACTION) sera créé automatiquement.</p> }
          <button type="submit" class="btn pri" [disabled]="busy() || !siteId">Enregistrer l'évaluation</button>
        </form>
      </div>
      <div class="box tw sec"><table aria-label="Évaluations par site">
        <thead><tr><th>Site</th><th>Date</th><th>Résultat</th><th>Preuve</th><th>Prochaine évaluation</th><th>Plan d'action</th></tr></thead>
        <tbody>@for (e of evals(); track e.id) {
          <tr><td>{{ e.siteName ?? siteName(e.siteId) }}</td><td class="mono">{{ date(e.at) }}</td>
            <td><span class="st" [class]="tone(e.result)"><i></i>{{ label(e.result) }}</span></td><td>{{ e.evidence ?? '—' }}</td>
            <td class="mono">{{ date(e.nextReview) }}</td><td><eq-action-links [actions]="e.actions" empty="—" /></td></tr>
        } @empty { <tr><td colspan="6" class="note">Pas encore évalué.</td></tr> }</tbody></table></div>
      <p class="note">Dernière évaluation par site : @for (e of latest(); track e.id) { {{ e.siteName ?? siteName(e.siteId) }} — {{ label(e.result) }} · }</p>
      <p><a class="lnk" routerLink="/veille">← Registre</a></p>
    }
  `,
  styles: `h3 { margin: 0 0 10px; font-size: 15px; } .lst { padding-left: 18px; li { margin-bottom: 6px; } }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglementFicheComponent {
  private readonly api = inject(RegulationsService);
  private readonly auth = inject(AuthService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly results = EVAL_RESULTS;
  protected readonly date = formatDate;
  protected readonly label = evalLabel;
  protected readonly tone = evalTone;
  protected readonly sites = computed(() => this.auth.me()?.sites ?? []);
  protected readonly reg = signal<Regulation | null>(null);
  protected readonly evals = signal<RegEvaluation[]>([]);
  protected readonly latest = computed(() => [...latestBySite(this.evals()).values()]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected siteId: string | null = this.auth.me()?.sites?.[0]?.id ?? null;
  protected result = 'conforme';
  protected evidence = '';
  protected comment = '';

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const r = await this.api.get(this.id);
      this.reg.set(r);
      this.evals.set(r.evaluations);
    } catch (e) {
      this.error.set(errorMessage(e, 'Texte introuvable.'));
    }
  }

  async evaluate(): Promise<void> {
    this.busy.set(true);
    try {
      const e = await this.api.evaluate(this.id, { siteId: this.siteId!, result: this.result, evidence: this.evidence, comment: this.comment });
      this.error.set(null);
      await this.load();
      if (!this.evals().some((x) => x.id === e.id)) this.evals.update((l) => [...l, e]);
      this.evidence = '';
      this.comment = '';
    } catch (e) {
      this.error.set(errorMessage(e, "L'évaluation n'a pas pu être enregistrée."));
    } finally {
      this.busy.set(false);
    }
  }
  protected siteName(id: string | null): string { return this.sites().find((s) => s.id === id)?.name ?? id ?? '—'; }
  protected statusLabel(s: string): string { return REG_STATUS[s] ?? s; }
  /** Seuls les liens http(s) sont rendus cliquables. */
  protected safeUrl(u: string | null): string | null { return u && /^https?:\/\//i.test(u) ? u : null; }
}
