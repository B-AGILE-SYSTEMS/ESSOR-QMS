import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DashboardSummary, MyTask, recordPath } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { formatDate, formatMad, statusTone } from '../../core/labels';
import { toM4Kpis } from '../../core/m4.models';
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
    imports: [RouterLink],
    templateUrl: './mes-actions.component.html',
    styleUrl: './mes-actions.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MesActionsComponent {
  pathOf(t: MyTask): string {
    return recordPath(t.recordType ?? t.type, t.id);
  }

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
      ...this.m4Kpis(s),
    ];
  });
  /** KPI M4 (conformité réglementaire, étalonnages), affichés quand le serveur les fournit. */
  private m4Kpis(s: DashboardSummary): Kpi[] {
    const m = toM4Kpis(s as unknown as Record<string, unknown>);
    const out: Kpi[] = [];
    if (m.regulatoryCompliance !== null) {
      out.push({ k: 'Conformité réglementaire', v: `${Math.round(m.regulatoryCompliance)} %`, d: 'textes applicables évalués', dom: false });
    }
    if (m.calibrationsDue15 !== null) {
      const late = m.calibrationsOverdue ?? 0;
      out.push({ k: 'Étalonnages sous 15 jours', v: String(m.calibrationsDue15), d: late ? `${late} échu(s)` : 'aucun échu', dom: late > 0 });
    }
    return out;
  }
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
