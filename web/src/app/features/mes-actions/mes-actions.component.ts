import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DashboardSummary, MyTask } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { formatDate, formatMad, statusTone } from '../../core/labels';
import { RecordsService } from '../../core/records.service';

export type Filtre = 'a-faire' | 'en-retard' | 'a-valider';

/** Statuts où l'utilisateur doit décider (qualification, vérification d'efficacité). */
const A_VALIDER = new Set(['DECLAREE', 'VERIFICATION']);

export function matches(t: MyTask, f: Filtre): boolean {
  if (f === 'en-retard') return t.overdue;
  if (f === 'a-valider') return A_VALIDER.has(t.status);
  return true;
}

interface Kpi {
  k: string;
  v: string;
  d: string;
  dom: boolean;
}

/** Accueil « Mes actions » : `/api/my-tasks` (trié par échéance) et `/api/dashboard/summary`. */
@Component({
  selector: 'eq-mes-actions',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './mes-actions.component.html',
  styleUrl: './mes-actions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MesActionsComponent {
  private readonly records = inject(RecordsService);

  protected readonly tasks = signal<MyTask[]>([]);
  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly filtre = signal<Filtre>('a-faire');
  protected readonly filtres: { id: Filtre; label: string }[] = [
    { id: 'a-faire', label: 'À faire' },
    { id: 'en-retard', label: 'En retard' },
    { id: 'a-valider', label: 'À valider' },
  ];
  protected readonly visibles = computed(() => this.tasks().filter((t) => matches(t, this.filtre())));
  protected readonly kpis = computed<Kpi[]>(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      { k: 'Actions en retard', v: String(s.actionsOverdue), d: s.actionsOverdue ? 'à traiter en priorité' : 'aucun retard', dom: s.actionsOverdue > 0 },
      { k: 'NC ouvertes', v: String(s.ncOpen), d: `${s.ncToQualify} à qualifier`, dom: false },
      { k: 'NC à qualifier', v: String(s.ncToQualify), d: 'déclarées, non qualifiées', dom: false },
      { k: 'Coût NC (mois)', v: formatMad(s.ncCostMonth), d: 'MAD', dom: false },
    ];
  });
  protected readonly today = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full' }).format(new Date());
  protected readonly date = formatDate;
  protected readonly tone = statusTone;

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [tasks, summary] = await Promise.all([this.records.myTasks(), this.records.summary()]);
      this.tasks.set(tasks);
      this.summary.set(summary);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger vos actions.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected count(f: Filtre): number {
    return this.tasks().filter((t) => matches(t, f)).length;
  }
}
