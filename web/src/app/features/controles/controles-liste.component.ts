import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { formatDateTime } from '../../core/labels';
import { INSPECTION_STATUS_LABELS, Inspection, pointLabel, statusLabel } from '../../core/m3b.models';
import { InspectionsService } from '../../core/m3b.service';

const TABS = [
  { code: 'A_FAIRE', label: 'À faire' }, { code: 'EN_COURS', label: 'En cours' }, { code: 'TERMINE', label: 'Historique' },
];

/** Terrain → Contrôles : à faire, en cours, historique. */
@Component({
  selector: 'eq-controles-liste',
  imports: [RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Terrain</span><h2>Contrôles</h2></div>
      <a class="btn" routerLink="/controles/carte">Carte de contrôle</a></div>
    <div class="tabs" role="tablist" aria-label="Contrôles">
      @for (t of tabs; track t.code) {
        <button type="button" role="tab" [attr.aria-selected]="tab() === t.code" (click)="load(t.code)">{{ t.label }}</button>
      }
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw"><table>
      <thead><tr><th>Lot</th><th>Point de flux</th><th class="r">Échantillon</th><th>Statut</th><th>Décision</th><th>Créé</th></tr></thead>
      <tbody>@for (i of items(); track i.id) {
        <tr class="go" (click)="open(i)"><td><a class="lnk mono" [routerLink]="'/controles/' + i.id">{{ i.lotNumero ?? i.lotId }}</a></td>
          <td>{{ point(i.point) }}</td><td class="r mono">{{ i.sampleSize }}</td><td>{{ insp(i.status) }}</td>
          <td>{{ i.decision ? label(i.decision) : '—' }}</td><td class="mono">{{ dt(i.createdAt) }}</td></tr>
      } @empty { <tr><td colspan="6" class="note">Aucun contrôle.</td></tr> }</tbody></table></div>
  `,
  styles: `
    .tabs { display: flex; border-bottom: var(--bw) solid var(--line); margin-bottom: 14px;
      button { background: none; border: 0; border-bottom: 3px solid transparent; padding: 10px 14px; font-weight: 600; cursor: pointer; color: var(--ink2); }
      button[aria-selected='true'] { color: var(--ink); border-color: var(--acc); } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ControlesListeComponent {
  private readonly api = inject(InspectionsService);
  private readonly router = inject(Router);
  protected readonly tabs = TABS;
  protected readonly point = pointLabel;
  protected readonly label = statusLabel;
  protected readonly dt = formatDateTime;
  protected readonly insp = (s: string) => INSPECTION_STATUS_LABELS[s] ?? s;
  protected readonly tab = signal('A_FAIRE');
  protected readonly items = signal<Inspection[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.load('A_FAIRE');
  }

  async load(status: string): Promise<void> {
    this.tab.set(status);
    try {
      this.items.set(await this.api.list({ status }));
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les contrôles.'));
    }
  }

  protected open(i: Inspection): void {
    void this.router.navigateByUrl(`/controles/${i.id}`);
  }
}
