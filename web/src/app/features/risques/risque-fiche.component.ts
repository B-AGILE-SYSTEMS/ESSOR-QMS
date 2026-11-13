import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { formatDate, formatDateTime } from '../../core/labels';
import { refLabel } from '../../core/m3b.models';
import { CRITICITY_LABELS, Rating, Risk, RiskScale, criticityOf, riskTypeLabel } from '../../core/m4.models';
import { RisksService } from '../../core/m4.service';
import { ActionLinksComponent } from './action-links.component';

/** Fiche risque : cotations brute et résiduelle, mesures, actions M1, exigences, historique des cotations. */
@Component({
  selector: 'eq-risque-fiche',
  imports: [RouterLink, ActionLinksComponent],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (risk(); as r) {
      <div class="ph"><div><span class="ref">{{ r.reference ?? 'Risque' }} · {{ typeLabel(r.type) }} · {{ ref(r.site) }}</span><h2>{{ r.hazard }}</h2></div>
        <span class="crit-chip" data-testid="risk-level" [style.background]="color(r.residual ?? r.gross)">{{ level(r.residual ?? r.gross) }}</span></div>
      <div class="two">
        <div class="box pad"><dl class="kv">
          <dt>Unité / processus</dt><dd>{{ r.unit ?? '—' }}</dd>
          <dt>Situation</dt><dd>{{ r.situation ?? '—' }}</dd>
          <dt>Cotation brute</dt><dd class="mono">{{ rating(r.gross) }}</dd>
          <dt>Cotation résiduelle</dt><dd class="mono">{{ r.residual ? rating(r.residual) : '—' }}</dd>
          <dt>Mesures existantes</dt><dd>{{ r.measures ?? '—' }}</dd>
          <dt>Réévaluation</dt><dd class="mono">{{ date(r.reviewDate) }}</dd>
          <dt>Exigences liées</dt><dd>@for (q of r.requirements; track $index) { <span class="badge">{{ ref(q) }}</span> } @empty { — }</dd>
        </dl></div>
        <div class="box pad"><h3>Actions de maîtrise</h3><eq-action-links [actions]="r.actions" empty="Aucune action de maîtrise." /></div>
      </div>
      <div class="box tw sec"><table aria-label="Historique des cotations">
        <thead><tr><th>Date</th><th>Par</th><th>Brute</th><th>Résiduelle</th><th>Commentaire</th></tr></thead>
        <tbody>@for (h of r.history; track $index) {
          <tr><td class="mono">{{ dt(h.at) }}</td><td>{{ h.by ?? '—' }}</td><td class="mono">{{ h.gross ? rating(h.gross) : '—' }}</td>
            <td class="mono">{{ h.residual ? rating(h.residual) : '—' }}</td><td>{{ h.comment ?? '' }}</td></tr>
        } @empty { <tr><td colspan="5" class="note">Aucun historique.</td></tr> }</tbody></table></div>
      <p class="sec"><a class="lnk" routerLink="/risques">← Retour à la cartographie</a></p>
    }
  `,
  styles: `h3 { margin: 0 0 10px; font-size: 15px; } .crit-chip { padding: 4px 10px; color: #111; border: var(--bw) solid var(--line2); font-weight: 600; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RisqueFicheComponent {
  private readonly api = inject(RisksService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly risk = signal<Risk | null>(null);
  protected readonly scale = signal<RiskScale | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly ref = refLabel;
  protected readonly typeLabel = riskTypeLabel;
  protected readonly date = formatDate;
  protected readonly dt = formatDateTime;

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [r, s] = await Promise.all([this.api.get(this.id), this.api.scales().catch(() => [])]);
      this.risk.set(r);
      this.scale.set(s[0] ?? null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Risque introuvable.'));
    }
  }

  private threshold(r: Rating) {
    const s = this.scale();
    return s ? (s.thresholds.find((t) => t.level === r.level) ?? criticityOf(s, r.score)) : null;
  }
  protected level(r: Rating): string { return this.threshold(r)?.label ?? CRITICITY_LABELS[r.level ?? ''] ?? r.level ?? '—'; }
  protected color(r: Rating): string | null { return this.threshold(r)?.color ?? null; }
  protected rating(r: Rating): string {
    const dims = Object.entries(r.values).map(([k, v]) => `${this.scale()?.dimensions.find((d) => d.code === k)?.label ?? k} ${v}`).join(' × ');
    return `${dims}${dims ? ' = ' : ''}${r.score ?? '—'} (${this.level(r)})`;
  }
}
