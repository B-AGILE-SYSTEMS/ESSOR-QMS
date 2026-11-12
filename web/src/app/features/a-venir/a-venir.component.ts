import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/** Écran réservé aux modules métier des milestones suivants. */
@Component({
  selector: 'eq-a-venir',
  standalone: true,
  template: `
    <div class="ph"><div><span class="ref">{{ data.famille }}</span><h2>{{ data.titre }}</h2></div></div>
    <div class="box pad"><p class="note">Ce module arrive dans un prochain jalon.</p></div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AVenirComponent {
  protected readonly data = inject(ActivatedRoute).snapshot.data as { titre: string; famille: string };
}
