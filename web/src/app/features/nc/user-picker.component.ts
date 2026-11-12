import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { UserRef } from '../../core/api.models';
import { RecordsService } from '../../core/records.service';

const DEBOUNCE_MS = 250;
let seq = 0;

/** Sélection d'un utilisateur du tenant (`GET /api/users?q=`), en liste déroulante filtrée. */
@Component({
  selector: 'eq-user-picker',
  standalone: true,
  template: `
    <div class="up">
      <input
        #q
        [id]="inputId"
        role="combobox"
        [attr.aria-expanded]="open()"
        [attr.aria-controls]="listId"
        autocomplete="off"
        [value]="text()"
        [placeholder]="placeholder()"
        [attr.aria-label]="label()"
        (input)="onInput(q.value)"
        (focus)="onInput(q.value)"
        (keydown.escape)="open.set(false)"
      />
      @if (open()) {
        <ul [id]="listId" role="listbox" [attr.aria-label]="label()">
          @for (u of results(); track u.id) {
            <li role="option" aria-selected="false" tabindex="0" (click)="pick(u)" (keydown.enter)="pick(u)">{{ u.displayName }}</li>
          } @empty {
            <li class="note">Aucun utilisateur.</li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .up { position: relative; }
    input { width: 100%; border: var(--bw) solid var(--line2); background: var(--surf); padding: 9px 10px; min-height: 40px; }
    ul { position: absolute; z-index: 30; left: 0; right: 0; margin: 2px 0 0; padding: 4px 0; list-style: none;
      background: var(--surf); border: var(--bw) solid var(--line2); max-height: 220px; overflow: auto; }
    li { padding: 8px 10px; cursor: pointer; font-size: 14px; }
    li[role='option']:hover, li[role='option']:focus { background: var(--surf2); outline: 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserPickerComponent {
  private readonly records = inject(RecordsService);
  readonly label = input('Responsable');
  readonly placeholder = input('Rechercher un utilisateur…');
  readonly picked = output<UserRef>();

  protected readonly inputId = `eq-up-${++seq}`;
  protected readonly listId = `${this.inputId}-list`;
  protected readonly text = signal('');
  protected readonly results = signal<UserRef[]>([]);
  protected readonly open = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.timer && clearTimeout(this.timer));
  }

  protected onInput(v: string): void {
    this.text.set(v);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.lookup(v.trim()), DEBOUNCE_MS);
  }

  private async lookup(q: string): Promise<void> {
    try {
      this.results.set(await this.records.users(q));
    } catch {
      this.results.set([]);
    }
    this.open.set(true);
  }

  protected pick(u: UserRef): void {
    this.text.set(u.displayName);
    this.open.set(false);
    this.picked.emit(u);
  }
}
