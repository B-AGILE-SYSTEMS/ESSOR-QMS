import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { formatDate } from '../../core/labels';
import { refLabel } from '../../core/m3b.models';
import { INSTRUMENT_STATUS, Instrument, dueLabel, instrumentTone } from '../../core/m4.models';
import { InstrumentsService } from '../../core/m4.service';

/** Ressources → Équipements : parc d'instruments de mesure, statut et échéance d'étalonnage. */
@Component({
  selector: 'eq-equipements',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Ressources · métrologie</span><h2>Équipements de mesure</h2></div></div>
    <div class="flt"><label class="field"><span>Statut</span><select name="st" [(ngModel)]="status" (ngModelChange)="load()">
      <option [ngValue]="null">Tous</option>@for (s of statuses; track s[0]) { <option [ngValue]="s[0]">{{ s[1] }}</option> }</select></label></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw"><table aria-label="Parc d'instruments">
      <thead><tr><th>Code</th><th>Désignation</th><th>Type</th><th>Site</th><th>Emplacement</th><th class="r">EMT</th><th>Échéance</th><th>Statut</th></tr></thead>
      <tbody>@for (i of items(); track i.id) {
        <tr class="go" (click)="open(i)"><td><a class="lnk mono" [routerLink]="'/equipements/' + i.id">{{ i.code }}</a></td><td>{{ i.designation }}</td>
          <td>{{ i.type ?? '—' }}</td><td>{{ ref(i.site) }}</td><td>{{ i.location ?? '—' }}</td><td class="r mono">{{ i.emt ?? '—' }}</td>
          <td class="mono">{{ date(i.nextDue) }} <span class="st" [class]="due(i.nextDue).tone"><i></i>{{ due(i.nextDue).text }}</span></td>
          <td><span class="st" [class]="tone(i.status)"><i></i>{{ statusLabel(i.status) }}</span></td></tr>
      } @empty { <tr><td colspan="8" class="note">Aucun instrument.</td></tr> }</tbody></table></div>
  `,
  styles: `.flt .field { max-width: 240px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EquipementsComponent {
  private readonly api = inject(InstrumentsService);
  private readonly router = inject(Router);
  protected readonly statuses = Object.entries(INSTRUMENT_STATUS);
  protected readonly ref = refLabel;
  protected readonly date = formatDate;
  protected readonly due = (d: string | null) => dueLabel(d);
  protected readonly tone = instrumentTone;
  protected status: string | null = null;
  protected readonly items = signal<Instrument[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.load();
  }
  async load(): Promise<void> {
    try {
      this.items.set(await this.api.list({ status: this.status }));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger le parc.'));
    }
  }
  protected statusLabel(s: string): string { return INSTRUMENT_STATUS[s] ?? s; }
  protected open(i: Instrument): void { void this.router.navigateByUrl(`/equipements/${i.id}`); }
}
