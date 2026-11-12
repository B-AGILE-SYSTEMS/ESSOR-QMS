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
      {
        path: 'non-conformites/declarer',
        loadComponent: () => import('./features/nc/nc-declarer.component').then((m) => m.NcDeclarerComponent),
        data: { crumbs: ['Terrain', 'Non-conformités', 'Déclarer'] },
      },
      {
        path: 'non-conformites/:id',
        loadComponent: () => import('./features/nc/nc-fiche.component').then((m) => m.NcFicheComponent),
        data: { crumbs: ['Terrain', 'Non-conformités', 'Fiche'] },
      },
      {
        path: 'non-conformites',
        loadComponent: () => import('./features/nc/nc-liste.component').then((m) => m.NcListeComponent),
        data: { crumbs: ['Terrain', 'Non-conformités'] },
      },
      aVenir('audits', 'Terrain', 'Audits & inspections'),
      aVenir('indicateurs', 'Pilotage', 'Indicateurs'),
      aVenir('risques', 'Pilotage', 'Risques & conformité'),
      aVenir('equipements', 'Ressources', 'Équipements'),
      aVenir('documents', 'Ressources', 'Documents'),
      {
        path: 'parametrage/workflows',
        canMatch: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/parametrage/workflows.component').then((m) => m.WorkflowsComponent),
        data: { crumbs: ['Administration', 'Paramétrage', 'Workflows'] },
      },
      {
        path: 'parametrage/notifications',
        canMatch: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/parametrage/regles-notification.component').then((m) => m.ReglesNotificationComponent),
        data: { crumbs: ['Administration', 'Paramétrage', 'Règles de notification'] },
      },
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
