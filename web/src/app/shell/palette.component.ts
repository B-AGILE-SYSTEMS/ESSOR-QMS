import {
  AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, computed, inject, output, signal, viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { NAV_FAMILIES, canSee } from './navigation.model';

interface Command {
  label: string;
  group: string;
  path: string;
}

/** Palette de commandes (Ctrl K) : navigation rapide. La recherche plein texte arrive avec le métier. */
@Component({
  selector: 'eq-palette',
  standalone: true,
  template: `
    <div class="pal">
      <button type="button" class="backdrop" aria-label="Fermer la palette" tabindex="-1" (click)="closed.emit()"></button>
      <div class="in" role="dialog" aria-modal="true" aria-label="Commande rapide" tabindex="-1" (keydown.escape)="closed.emit()">
        <input
          #q
          [value]="query()"
          (input)="query.set(q.value)"
          (keydown.enter)="go(results()[0])"
          placeholder="Rechercher ou taper une commande…"
          aria-label="Commande"
          autocomplete="off"
        />
        <ul>
          @for (c of results(); track c.path) {
            <li><button type="button" (click)="go(c)">{{ c.label }}<small>{{ c.group }}</small></button></li>
          } @empty {
            <li class="note empty">Aucun résultat.</li>
          }
        </ul>
      </div>
    </div>
  `,
  styles: `
    .pal { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; z-index: 80; align-items: flex-start; justify-content: center; padding: 80px 16px; }
    .backdrop { position: absolute; inset: 0; background: none; border: 0; padding: 0; cursor: default; }
    .backdrop:hover { background: none; }
    .in { position: relative; width: min(620px, 100%); background: var(--surf); color: var(--ink); border: var(--bw) solid var(--line2); }
    input { width: 100%; border: 0; border-bottom: 2px solid var(--ink); padding: 14px 16px; font-size: 16px; background: var(--surf); outline: 0; }
    ul { list-style: none; margin: 0; padding: 6px 0; max-height: 340px; overflow: auto; }
    button { width: 100%; text-align: left; background: none; border: 0; padding: 10px 16px; display: flex; gap: 10px; cursor: pointer; font-size: 14px; }
    button:hover, button:focus { background: var(--surf2); outline: 0; }
    small { margin-left: auto; color: var(--ink2); font-family: var(--mono); }
    .empty { padding: 10px 16px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaletteComponent implements AfterViewInit {
  readonly closed = output<void>();
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('q');
  protected readonly query = signal('');

  private readonly commands: Command[] = [
    { label: 'Déclarer une non-conformité', group: 'Action', path: '/non-conformites/declarer' },
    ...NAV_FAMILIES.flatMap((f) =>
      f.items.filter((i) => canSee(i, (r) => this.auth.hasRole(r))).map((i) => ({ label: i.label, group: f.label, path: i.path })),
    ),
  ];

  protected readonly results = computed(() => {
    const q = this.query().trim().toLowerCase();
    return q ? this.commands.filter((c) => c.label.toLowerCase().includes(q)) : this.commands;
  });

  ngAfterViewInit(): void {
    this.input().nativeElement.focus();
  }

  protected go(c: Command | undefined): void {
    if (!c) return;
    void this.router.navigateByUrl(c.path);
    this.closed.emit();
  }
}
