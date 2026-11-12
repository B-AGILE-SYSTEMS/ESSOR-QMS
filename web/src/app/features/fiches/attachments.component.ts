import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ATTACHMENT_MAX_BYTES, ATTACHMENT_TYPES, Attachment } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { formatDateTime } from '../../core/labels';
import { RecordsService, attachmentUrl } from '../../core/records.service';

export function isImage(a: Pick<Attachment, 'contentType'>): boolean {
  return a.contentType === 'image/jpeg' || a.contentType === 'image/png';
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} Ko`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`;
}

/** Contrôle côté client (le serveur revérifie le contenu réel). Renvoie le message d'erreur ou null. */
export function checkFile(f: File): string | null {
  if (!(ATTACHMENT_TYPES as readonly string[]).includes(f.type)) return `« ${f.name} » : seuls JPEG, PNG et PDF sont acceptés.`;
  if (f.size > ATTACHMENT_MAX_BYTES) return `« ${f.name} » dépasse 10 Mo.`;
  return null;
}

/** Onglet « Pièces jointes » : vignettes, aperçu, téléchargement, ajout. */
@Component({
  selector: 'eq-attachments',
  standalone: true,
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (canAdd()) {
      <label class="btn add">＋ Ajouter une pièce jointe
        <input type="file" accept="image/jpeg,image/png,application/pdf" multiple [disabled]="busy()"
          aria-label="Ajouter une pièce jointe" (change)="onFiles($event)" />
      </label>
      <span class="note"> JPEG, PNG ou PDF, 10 Mo maximum.</span>
    }
    <ul class="grid">
      @for (a of items(); track a.id) {
        <li class="box att">
          @if (image(a)) {
            <button type="button" class="thumb" [attr.aria-label]="'Aperçu de ' + a.fileName" (click)="preview.set(a)">
              <img [src]="url(a)" [alt]="a.fileName" loading="lazy" />
            </button>
          } @else {
            <a class="thumb doc" [href]="url(a)" target="_blank" rel="noopener">PDF</a>
          }
          <div class="meta">
            <b class="name" [title]="a.fileName">{{ a.fileName }}</b>
            <span class="note">{{ size(a.size) }} · {{ dateTime(a.createdAt) }} · {{ a.createdByName ?? '—' }}</span>
            <a class="lnk" [href]="url(a)" [attr.download]="a.fileName">Télécharger</a>
          </div>
        </li>
      } @empty {
        <li class="note">{{ loading() ? 'Chargement…' : 'Aucune pièce jointe.' }}</li>
      }
    </ul>
    @if (preview(); as p) {
      <div class="overlay" role="dialog" aria-modal="true" [attr.aria-label]="p.fileName">
        <figure>
          <img [src]="url(p)" [alt]="p.fileName" />
          <figcaption>{{ p.fileName }}
            <a class="lnk" [href]="url(p)" [attr.download]="p.fileName">Télécharger</a>
            <button type="button" class="btn" (click)="preview.set(null)">Fermer</button>
          </figcaption>
        </figure>
      </div>
    }
  `,
  styles: `
    .add { position: relative; overflow: hidden; cursor: pointer; }
    .add input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
    .grid { list-style: none; padding: 0; margin: 14px 0 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
    .att { display: flex; flex-direction: column; }
    .thumb { display: flex; align-items: center; justify-content: center; height: 140px; background: var(--bg); border: 0; padding: 0; cursor: zoom-in; }
    .thumb img { max-width: 100%; max-height: 140px; object-fit: cover; }
    .doc { font-family: var(--mono); font-weight: 700; font-size: 22px; color: var(--ink2); text-decoration: none; cursor: pointer; }
    .meta { padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .overlay { position: fixed; inset: 0; background: rgb(0 0 0 / 75%); display: flex; align-items: center; justify-content: center; z-index: 50; }
    .overlay figure { margin: 0; background: var(--surf); padding: 10px; max-width: 92vw; max-height: 92vh; }
    .overlay img { max-width: 88vw; max-height: 80vh; display: block; }
    .overlay figcaption { display: flex; gap: 12px; align-items: center; padding-top: 8px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttachmentsComponent {
  private readonly records = inject(RecordsService);
  readonly recordId = input.required<string>();
  readonly canAdd = input(true);

  protected readonly items = signal<Attachment[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly preview = signal<Attachment | null>(null);
  protected readonly url = (a: Attachment) => attachmentUrl(a.id);
  protected readonly image = isImage;
  protected readonly size = formatSize;
  protected readonly dateTime = formatDateTime;

  constructor() {
    effect(() => {
      const id = this.recordId();
      untracked(() => void this.load(id));
    });
  }

  async load(id = this.recordId()): Promise<void> {
    this.loading.set(true);
    try {
      this.items.set(await this.records.attachments(id));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les pièces jointes.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async onFiles(ev: Event): Promise<void> {
    const inputEl = ev.target as HTMLInputElement;
    const files = Array.from(inputEl.files ?? []);
    inputEl.value = '';
    await this.upload(files);
  }

  async upload(files: File[]): Promise<void> {
    this.error.set(null);
    const rejected = files.map(checkFile).filter((m): m is string => !!m);
    const ok = files.filter((f) => !checkFile(f));
    this.busy.set(true);
    try {
      for (const f of ok) {
        const a = await this.records.addAttachment(this.recordId(), f);
        this.items.update((list) => [...list, a]);
      }
    } catch (e) {
      rejected.push(errorMessage(e, "Échec de l'envoi de la pièce jointe."));
    } finally {
      this.busy.set(false);
    }
    if (rejected.length) this.error.set(rejected.join(' '));
  }
}
