import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';
import { AVenirComponent } from './features/a-venir/a-venir.component';
import { ShellComponent } from './shell/shell.component';

const aVenir = (path: string, famille: string, titre: string) => ({
  path,
  component: AVenirComponent,
  data: { famille, titre, crumbs: [famille, titre] },
});

export const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'mes-actions' },
      {
        path: 'mes-actions',
        loadComponent: () => import('./features/mes-actions/mes-actions.component').then((m) => m.MesActionsComponent),
        data: { crumbs: ['Mon travail', 'Mes actions'] },
      },
      aVenir('non-conformites/declarer', 'Terrain', 'Déclarer une non-conformité'),
      aVenir('non-conformites', 'Terrain', 'Non-conformités'),
      aVenir('audits', 'Terrain', 'Audits & inspections'),
      aVenir('indicateurs', 'Pilotage', 'Indicateurs'),
      aVenir('risques', 'Pilotage', 'Risques & conformité'),
      aVenir('equipements', 'Ressources', 'Équipements'),
      aVenir('documents', 'Ressources', 'Documents'),
      {
        path: 'parametrage/apparence',
        canMatch: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/apparence/apparence.component').then((m) => m.ApparenceComponent),
        data: { crumbs: ['Administration', 'Paramétrage', 'Apparence'] },
      },
      { path: '**', redirectTo: 'mes-actions' },
    ],
  },
];
