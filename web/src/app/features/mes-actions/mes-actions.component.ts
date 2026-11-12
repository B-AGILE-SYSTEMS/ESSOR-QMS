import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { EXEMPLE_ACTIONS, EXEMPLE_KPIS, Filtre } from './mes-actions.exemple';

/** Accueil « Mes actions ». Données d'EXEMPLE : le métier (NC, actions) arrive en M1. */
@Component({
  selector: 'eq-mes-actions',
  standalone: true,
  templateUrl: './mes-actions.component.html',
  styleUrl: './mes-actions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MesActionsComponent {
  protected readonly kpis = EXEMPLE_KPIS;
  protected readonly filtre = signal<Filtre>('a-faire');
  protected readonly filtres: { id: Filtre; label: string }[] = [
    { id: 'a-faire', label: 'À faire' },
    { id: 'en-retard', label: 'En retard' },
    { id: 'a-valider', label: 'À valider' },
  ];
  protected readonly actions = computed(() => EXEMPLE_ACTIONS.filter((a) => a.filtres.includes(this.filtre())));
  protected readonly today = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full' }).format(new Date());

  protected count(f: Filtre): number {
    return EXEMPLE_ACTIONS.filter((a) => a.filtres.includes(f)).length;
  }
}
