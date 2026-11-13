import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';
import { AUDIT_VIEW, DOCUMENT_VIEW, INCIDENT_VIEW, NC_VIEW } from './features/fiches/record-type-view';
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
        data: { crumbs: ['Terrain', 'Non-conformités', 'Fiche'], view: NC_VIEW },
      },
      {
        path: 'non-conformites',
        loadComponent: () => import('./features/nc/nc-liste.component').then((m) => m.NcListeComponent),
        data: { crumbs: ['Terrain', 'Non-conformités'], view: NC_VIEW },
      },
      {
        path: 'incidents/:id',
        loadComponent: () => import('./features/nc/nc-fiche.component').then((m) => m.NcFicheComponent),
        data: { crumbs: ['Terrain', 'Incidents & sécurité', 'Fiche'], view: INCIDENT_VIEW },
      },
      {
        path: 'incidents',
        loadComponent: () => import('./features/nc/nc-liste.component').then((m) => m.NcListeComponent),
        data: { crumbs: ['Terrain', 'Incidents & sécurité'], view: INCIDENT_VIEW },
      },
      {
        path: 'audits/:id',
        loadComponent: () => import('./features/nc/nc-fiche.component').then((m) => m.NcFicheComponent),
        data: { crumbs: ['Terrain', 'Audits & inspections', 'Fiche'], view: AUDIT_VIEW },
      },
      {
        path: 'audits',
        loadComponent: () => import('./features/audits/audits-programme.component').then((m) => m.AuditsProgrammeComponent),
        data: { crumbs: ['Terrain', 'Audits & inspections'] },
      },
      aVenir('indicateurs', 'Pilotage', 'Indicateurs'),
      aVenir('risques', 'Pilotage', 'Risques & conformité'),
      aVenir('equipements', 'Ressources', 'Équipements'),
      {
        path: 'documents/:id',
        loadComponent: () => import('./features/nc/nc-fiche.component').then((m) => m.NcFicheComponent),
        data: { crumbs: ['Ressources', 'Documents', 'Fiche'], view: DOCUMENT_VIEW },
      },
      {
        path: 'documents',
        loadComponent: () => import('./features/documents/documents-liste.component').then((m) => m.DocumentsListeComponent),
        data: { crumbs: ['Ressources', 'Documents'] },
      },
      {
        path: 'parametrage/referentiels',
        canMatch: [roleGuard('ADMIN', 'QUALITY_MANAGER')],
        loadComponent: () => import('./features/referentiels/referentiels.component').then((m) => m.ReferentielsComponent),
        data: { crumbs: ['Administration', 'Paramétrage', 'Référentiels'] },
      },
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
