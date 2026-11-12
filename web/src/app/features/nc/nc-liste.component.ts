import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { RecordSummary, SEVERITIES, Severity, WorkflowState } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { SEVERITY_LABELS, formatDate, statusTone } from '../../core/labels';
import { RecordsService } from '../../core/records.service';

export const PAGE_SIZE = 20;

/** Liste des non-conformités : filtres (statut, gravité, site, texte) et pagination serveur. */
@Component({
  selector: 'eq-nc-liste',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="ph">
      <div><span class="ref">Terrain</span><h2>Non-conformités</h2></div>
      <a class="btn pri" routerLink="/non-conformites/declarer">＋ Déclarer</a>
    </div>
    <form class="filters box pad" role="search" (submit)="$event.preventDefault(); search()">
      <label class="field"><span>Recherche</span>
        <input #q name="q" [value]="query()" (input)="query.set(q.value)" placeholder="Référence, titre…" />
      </label>
      <label class="field"><span>Étape</span>
        <select #st name="status" (change)="status.set(st.value); search()">
          <option value="">Toutes</option>
          @for (s of states(); track s.code) { <option [value]="s.code">{{ s.label }}</option> }
        </select>
      </label>
      <label class="field"><span>Gravité</span>
        <select #sv name="severity" (change)="severity.set(sv.value); search()">
          <option value="">Toutes</option>
          @for (s of severities; track s) { <option [value]="s">{{ sevLabel[s] }}</option> }
        </select>
      </label>
      <label class="field"><span>Site</span>
        <select #si name="site" (change)="siteId.set(si.value); search()">
          <option value="">Tous les sites</option>
          @for (s of auth.me()?.sites ?? []; track s.id) { <option [value]="s.id">{{ s.name }}</option> }
        </select>
      </label>
      <button type="submit" class="btn">Filtrer</button>
    </form>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw">
      <table>
        <thead><tr><th>Réf.</th><th>Objet</th><th>Site</th><th>Gravité</th><th>Responsable</th><th>Échéance</th><th>Étape</th></tr></thead>
        <tbody>
          @for (r of items(); track r.id) {
            <tr class="go" (click)="open(r)">
              <td class="mono">{{ r.reference }}</td>
              <td><a class="lnk" [routerLink]="['/non-conformites', r.id]" (click)="$event.stopPropagation()">{{ r.title }}</a></td>
              <td>{{ r.siteName ?? '—' }}</td>
              <td>{{ r.severity ? sevLabel[r.severity] : '—' }}</td>
              <td>{{ r.ownerName ?? '—' }}</td>
              <td class="mono">{{ date(r.dueDate) }}</td>
              <td><span [class]="'st ' + tone(r.status)"><i></i>{{ r.statusLabel }}</span></td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="note">{{ loading() ? 'Chargement…' : 'Aucune non-conformité.' }}</td></tr>
          }
        </tbody>
      </table>
    </div>
    <nav class="pager" aria-label="Pagination">
      <span class="note">{{ total() }} fiche(s) · page {{ page() + 1 }} / {{ pages() }}</span>
      <button type="button" class="btn" [disabled]="page() === 0" (click)="go(page() - 1)">← Précédente</button>
      <button type="button" class="btn" [disabled]="page() + 1 >= pages()" (click)="go(page() + 1)">Suivante →</button>
    </nav>
  `,
  styles: `
    .filters { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr auto; gap: 12px; align-items: end; margin-bottom: 14px; }
    .filters .field { margin: 0; }
    .pager { display: flex; gap: 8px; align-items: center; justify-content: flex-end; margin-top: 12px; }
    .pager .note { margin-right: auto; }
    @media (max-width: 860px) { .filters { grid-template-columns: 1fr 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NcListeComponent {
  protected readonly auth = inject(AuthService);
  private readonly records = inject(RecordsService);
  private readonly router = inject(Router);

  protected readonly severities = SEVERITIES;
  protected readonly sevLabel: Record<Severity, string> = SEVERITY_LABELS;
  protected readonly date = formatDate;
  protected readonly tone = statusTone;

  protected readonly states = signal<WorkflowState[]>([]);
  protected readonly query = signal('');
  protected readonly status = signal('');
  protected readonly severity = signal('');
  protected readonly siteId = signal('');
  protected readonly page = signal(0);
  protected readonly items = signal<RecordSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.total() / PAGE_SIZE)));

  constructor() {
    this.records.workflow('NC').then((wf) => this.states.set(wf.states), () => undefined);
    void this.fetch();
  }

  search(): void {
    this.page.set(0);
    void this.fetch();
  }

  go(p: number): void {
    this.page.set(p);
    void this.fetch();
  }

  protected open(r: RecordSummary): void {
    void this.router.navigate(['/non-conformites', r.id]);
  }

  private async fetch(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const res = await this.records.list({
        type: 'NC', status: this.status(), severity: this.severity(), siteId: this.siteId(), q: this.query().trim(),
        page: this.page(), size: PAGE_SIZE,
      });
      this.items.set(res.items);
      this.total.set(res.total);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les non-conformités.'));
    } finally {
      this.loading.set(false);
    }
  }
}
