import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { recordPath } from '../../core/api.models';
import { ActionLink } from '../../core/m4.models';

/** Fiches ACTION M1 rattachées (lien vers la fiche). */
@Component({
  selector: 'eq-action-links',
  imports: [RouterLink],
  template: `@for (a of actions(); track a.id) {
      <a class="lnk mono act" [routerLink]="path(a.id)">{{ a.reference ?? 'Action' }}</a>@if (a.title) { <span class="note"> {{ a.title }}</span> }
      @if (a.status) { <span class="badge">{{ a.status }}</span> }<br />
    } @empty { <span class="note">{{ empty() }}</span> }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionLinksComponent {
  readonly actions = input.required<ActionLink[]>();
  readonly empty = input('Aucune action.');
  protected path(id: string): string { return recordPath('ACTION', id); }
}
