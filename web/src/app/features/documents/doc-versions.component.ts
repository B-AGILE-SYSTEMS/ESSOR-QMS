import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { errorMessage } from '../../core/api-error';
import { formatDateTime } from '../../core/labels';
import { AvailableTransition } from '../../core/api.models';
import { DOC_FILE_EXT, DOC_FILE_TYPES, DOC_MAX_BYTES, DocVersion, versionTransitions } from '../../core/m3.models';
import { DocumentsService, versionFileUrl } from '../../core/m3.service';
import { docTone } from './documents-liste.component';

/** Contrôle client d'un fichier de version (PDF/DOCX/XLSX ≤ 20 Mo) ; le serveur reste juge. */
export function checkDocFile(f: File): string | null {
  if (!(DOC_FILE_TYPES as readonly string[]).includes(f.type) && !DOC_FILE_EXT.test(f.name)) return 'Format refusé : seuls PDF, DOCX et XLSX.';
  if (f.size > DOC_MAX_BYTES) return 'Fichier de plus de 20 Mo.';
  return null;
}

/** Onglet « Versions » de la fiche document : historique, version en vigueur, visionneuse PDF, téléversement. */
@Component({
  selector: 'eq-doc-versions',
  imports: [FormsModule],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (canUpload()) {
      <form class="box pad up" (ngSubmit)="upload()">
        <label class="field"><span>Nouvelle version (PDF, DOCX, XLSX ≤ 20 Mo)</span>
          <input type="file" accept=".pdf,.docx,.xlsx" aria-label="Fichier de la nouvelle version" (change)="pick($event)" /></label>
        <label class="field"><span>Note de version</span><input name="note" [(ngModel)]="note" /></label>
        <button type="submit" class="btn" [disabled]="busy() || !file()">Téléverser</button>
      </form>
    }
    <div class="box tw"><table>
      <thead><tr><th>Version</th><th>Fichier</th><th>Note</th><th>Déposée</th><th>Statut</th><th></th></tr></thead>
      <tbody>
        @for (v of versions(); track v.number) {
          <tr [class.sel]="shown()?.number === v.number">
            <td class="mono"><b>{{ v.number }}</b>@if (v.current) { <span class="badge cur">en vigueur</span> }</td>
            <td>{{ v.fileName }}</td><td>{{ v.note || '—' }}</td>
            <td class="mono">{{ dt(v.createdAt) }}<br><span class="note">{{ v.createdByName ?? '' }}</span></td>
            <td><span [class]="'st ' + tone(v.status)"><i></i>{{ v.statusLabel ?? v.status }}</span></td>
            <td class="r">
              @for (t of trs(v); track t.code) {
                <button type="button" class="btn" [class.pri]="$first" [disabled]="busy() || t.missing.length > 0" (click)="transition(v, t)">{{ t.label }}</button>
              }
              @if (isPdf(v)) { <button type="button" class="btn" (click)="show(v)" [attr.aria-label]="'Consulter la version ' + v.number">Consulter</button> }
              <a class="btn ghost" [href]="url(v)" download>Télécharger</a>
            </td>
          </tr>
        } @empty { <tr><td colspan="6" class="note">Aucune version. Téléversez la première.</td></tr> }
      </tbody>
    </table></div>
    @if (viewer(); as src) {
      <div class="box view">
        <p class="note pad">Version {{ shown()!.number }} — {{ shown()!.fileName }}@if (shown()!.status === 'PERIME') { · <b>périmée</b> }</p>
        <iframe [src]="src" [title]="'Visionneuse ' + shown()!.fileName"></iframe>
      </div>
    }
  `,
  styles: `
    .up { display: grid; grid-template-columns: 1.4fr 2fr auto; gap: 12px; align-items: end; margin-bottom: 14px; .field { margin: 0; } }
    .cur { margin-left: 8px; border-color: var(--ok); color: var(--ok); }
    tr.sel td { background: var(--surf2); }
    td.r { white-space: nowrap; .btn { margin-left: 6px; } }
    .view { margin-top: 14px; .pad { padding: 8px 14px; margin: 0; } iframe { width: 100%; height: 640px; border: 0; border-top: var(--bw) solid var(--line); display: block; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocVersionsComponent {
  private readonly docs = inject(DocumentsService);
  private readonly sanitizer = inject(DomSanitizer);
  readonly docId = input.required<string>();
  /** Téléversement permis (fiche non verrouillée, pas de version en cours de circuit). */
  readonly canUpload = input(true);
  readonly changed = output<void>();

  protected readonly versions = signal<DocVersion[]>([]);
  protected readonly shown = signal<DocVersion | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected note = '';
  protected readonly dt = formatDateTime;
  protected readonly tone = docTone;
  // URL même origine construite à partir d'identifiants encodés (jamais d'une saisie libre) : iframe autorisée.
  protected readonly viewer = computed<SafeResourceUrl | null>(() => {
    const v = this.shown();
    return v ? this.sanitizer.bypassSecurityTrustResourceUrl(versionFileUrl(this.docId(), v.number)) : null;
  });

  constructor() {
    effect(() => {
      const id = this.docId();
      untracked(() => void this.load(id));
    });
  }

  async load(id = this.docId()): Promise<void> {
    try {
      const list = await this.docs.versions(id);
      this.versions.set(list);
      const cur = list.find((v) => v.current) ?? list[0];
      this.shown.set(cur && this.isPdf(cur) ? cur : null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les versions.'));
    }
  }

  protected readonly trs = versionTransitions;

  async transition(v: DocVersion, t: AvailableTransition): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.docs.versionTransition(this.docId(), v.number, t.code);
      await this.load();
      this.changed.emit();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }

  protected isPdf(v: DocVersion): boolean {
    return v.contentType === 'application/pdf' || /\.pdf$/i.test(v.fileName);
  }

  protected url(v: DocVersion): string {
    return versionFileUrl(this.docId(), v.number);
  }

  protected show(v: DocVersion): void {
    this.shown.set(v);
  }

  protected pick(ev: Event): void {
    const f = (ev.target as HTMLInputElement).files?.[0] ?? null;
    const err = f ? checkDocFile(f) : null;
    this.error.set(err);
    this.file.set(err ? null : f);
  }

  async upload(): Promise<void> {
    const f = this.file();
    if (!f) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.docs.addVersion(this.docId(), f, this.note.trim());
      this.note = '';
      this.file.set(null);
      await this.load();
      this.changed.emit();
    } catch (e) {
      this.error.set(errorMessage(e, 'Le téléversement a échoué.'));
    } finally {
      this.busy.set(false);
    }
  }
}
