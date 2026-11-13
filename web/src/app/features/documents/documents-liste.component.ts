import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate } from '../../core/labels';
import { DOC_TYPES, DOC_TYPE_LABELS, DocType, DocumentSummary } from '../../core/m3.models';
import { DocumentsService } from '../../core/m3.service';

export const DOC_STATUSES = [
  { code: 'REDACTION', label: 'Rédaction' }, { code: 'VERIFICATION', label: 'Vérification' },
  { code: 'APPROBATION', label: 'Approbation' }, { code: 'DIFFUSE', label: 'Diffusé' }, { code: 'PERIME', label: 'Périmé' },
];

/** Documents : liste filtrable (statut, type, texte) et création d'un document (fiche DOCUMENT). */
@Component({
  selector: 'eq-documents-liste',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph">
      <div><span class="ref">Ressources</span><h2>Documents</h2></div>
      <button type="button" class="btn pri" (click)="creating.set(!creating())">＋ Nouveau document</button>
    </div>
    @if (creating()) {
      <form class="box pad new" (ngSubmit)="create()" aria-label="Nouveau document">
        <label class="field"><span>Code</span><input name="code" [(ngModel)]="draft.code" placeholder="PR-QHSE-07" required /></label>
        <label class="field"><span>Titre</span><input name="title" [(ngModel)]="draft.title" required /></label>
        <label class="field"><span>Type</span><select name="docType" [(ngModel)]="draft.docType">
          @for (t of types; track t) { <option [value]="t">{{ typeLabel[t] }}</option> }</select></label>
        <label class="field"><span>Processus</span><input name="processus" [(ngModel)]="draft.processus" /></label>
        <label class="field"><span>Rôles destinataires</span><input name="roles" [(ngModel)]="draft.roles" placeholder="OPERATEUR, CHEF_EQUIPE" /></label>
        <label class="field"><span>Rôles vérificateurs</span><input name="verif" [(ngModel)]="draft.verifierRoles" /></label>
        <label class="field"><span>Rôles approbateurs</span><input name="appro" [(ngModel)]="draft.approverRoles" /></label>
        <label class="field"><span>Date de revue</span><input type="date" name="review" [(ngModel)]="draft.reviewDate" /></label>
        <button type="submit" class="btn pri" [disabled]="busy() || !draft.code.trim() || !draft.title.trim()">Créer</button>
      </form>
    }
    <form class="filters box pad" role="search" (submit)="$event.preventDefault(); load()">
      <label class="field"><span>Recherche</span><input name="q" [(ngModel)]="q" placeholder="Code, titre…" /></label>
      <label class="field"><span>Statut</span><select name="status" [(ngModel)]="status" (change)="load()">
        <option value="">Tous</option>@for (s of statuses; track s.code) { <option [value]="s.code">{{ s.label }}</option> }</select></label>
      <label class="field"><span>Type</span><select name="type" [(ngModel)]="type" (change)="load()">
        <option value="">Tous</option>@for (t of types; track t) { <option [value]="t">{{ typeLabel[t] }}</option> }</select></label>
      <button type="submit" class="btn">Filtrer</button>
    </form>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw"><table>
      <thead><tr><th>Code</th><th>Titre</th><th>Type</th><th>Processus</th><th>Version</th><th>Revue</th><th>Statut</th></tr></thead>
      <tbody>
        @for (d of items(); track d.id) {
          <tr class="go" (click)="open(d)">
            <td class="mono">{{ code(d) }}</td>
            <td><a class="lnk" [routerLink]="['/documents', d.id]" (click)="$event.stopPropagation()">{{ d.title }}</a></td>
            <td>{{ typeOf(d) }}</td>
            <td>{{ d.data?.['processus'] ?? '—' }}</td>
            <td class="mono">{{ d.currentVersion?.number ?? '—' }}</td>
            <td class="mono">{{ date($any(d.data?.['reviewDate'])) }}</td>
            <td><span [class]="'st ' + tone(d.status)"><i></i>{{ d.statusLabel }}</span></td>
          </tr>
        } @empty { <tr><td colspan="7" class="note">{{ loading() ? 'Chargement…' : 'Aucun document.' }}</td></tr> }
      </tbody>
    </table></div>
  `,
  styles: `
    .new { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0 14px; align-items: end; margin-bottom: 14px; }
    .filters { display: grid; grid-template-columns: 2fr 1fr 1fr auto; gap: 12px; align-items: end; margin-bottom: 14px; .field { margin: 0; } }
    @media (max-width: 860px) { .new, .filters { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsListeComponent {
  private readonly auth = inject(AuthService);
  private readonly docs = inject(DocumentsService);
  private readonly router = inject(Router);

  protected readonly types = DOC_TYPES;
  protected readonly typeLabel = DOC_TYPE_LABELS;
  protected readonly statuses = DOC_STATUSES;
  protected readonly date = formatDate;
  protected readonly tone = docTone;
  protected q = '';
  protected status = '';
  protected type = '';
  protected readonly items = signal<DocumentSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly creating = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected draft = {
    code: '', title: '', docType: 'procedure' as DocType, processus: '', roles: '', reviewDate: '', verifierRoles: 'QUALITY_MANAGER', approverRoles: 'ADMIN',
  };

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.items.set(await this.docs.list({ status: this.status, type: this.type, q: this.q.trim() }));
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les documents.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected code(d: DocumentSummary): string {
    return (d.data?.['code'] as string | undefined) ?? d.reference;
  }

  protected typeOf(d: DocumentSummary): string {
    const t = d.data?.['docType'] as DocType | undefined;
    return t ? (DOC_TYPE_LABELS[t] ?? t) : '—';
  }

  protected open(d: DocumentSummary): void {
    void this.router.navigate(['/documents', d.id]);
  }

  async create(): Promise<void> {
    const d = this.draft;
    const siteId = this.auth.me()?.sites[0]?.id ?? '';
    this.busy.set(true);
    try {
      const roles = split(d.roles);
      const rec = await this.docs.create({
        code: d.code.trim(), title: d.title.trim(), docType: d.docType, processus: d.processus.trim(), siteId: siteId || null,
        recipients: { roles, siteIds: [] }, verifierRoles: split(d.verifierRoles), approverRoles: split(d.approverRoles),
        reviewDate: d.reviewDate || null,
      });
      await this.router.navigate(['/documents', rec.id]);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de créer le document.'));
    } finally {
      this.busy.set(false);
    }
  }
}

function split(s: string): string[] {
  return s.split(',').map((x) => x.trim()).filter(Boolean);
}

/** Ton visuel d'un statut documentaire. */
export function docTone(status: string): 'crit' | 'warn' | 'info' | 'ok' {
  if (status === 'DIFFUSE') return 'ok';
  if (status === 'PERIME') return 'crit';
  if (status === 'REDACTION') return 'info';
  return 'warn';
}
