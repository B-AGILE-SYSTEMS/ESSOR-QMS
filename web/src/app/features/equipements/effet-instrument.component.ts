import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { formatDate, formatDateTime } from '../../core/labels';
import { pointLabel, qualityTone, statusLabel } from '../../core/m3b.models';
import { Impact, Instrument, lotsToQuarantine } from '../../core/m4.models';
import { InstrumentsService } from '../../core/m4.service';

/**
 * Effet instrument : contrôles et lots mesurés avec l'instrument depuis son dernier étalonnage conforme ;
 * mise en quarantaine pour réévaluation par `POST /api/instruments/{id}/impact/quarantine` (le serveur passe par l'API lots M3b).
 */
@Component({
  selector: 'eq-effet-instrument',
  imports: [FormsModule, RouterLink],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="ph"><div><span class="ref mono">{{ inst()?.code }} · {{ inst()?.designation }}</span><h2>Effet instrument</h2></div>
      <a class="btn" [routerLink]="'/equipements/' + id">← Fiche instrument</a></div>
    @if (impact(); as im) {
      <div class="box pad sum" data-testid="impact-summary">Depuis le dernier étalonnage conforme du <b class="mono">{{ date(im.since) }}</b> :
        <b>{{ im.inspections.length }}</b> contrôle(s) et <b>{{ im.lots.length }}</b> lot(s) concernés.</div>
      <div class="two">
        <div class="box tw"><table aria-label="Contrôles concernés">
          <thead><tr><th>Contrôle</th><th>Lot</th><th>Point</th><th>Date</th></tr></thead>
          <tbody>@for (c of im.inspections; track c.id) {
            <tr><td><a class="lnk mono" [routerLink]="'/controles/' + c.id">{{ c.id }}</a></td><td class="mono">{{ c.lotNumero ?? '—' }}</td>
              <td>{{ point(c.point) }}</td><td class="mono">{{ dt(c.at) }}</td></tr>
          } @empty { <tr><td colspan="4" class="note">Aucun contrôle.</td></tr> }</tbody></table></div>
        <div class="box tw"><table aria-label="Lots concernés">
          <thead><tr><th>Lot</th><th>Article</th><th class="r">Quantité</th><th>Statut</th></tr></thead>
          <tbody>@for (l of im.lots; track l.id) {
            <tr><td><a class="lnk mono" [routerLink]="'/lots/' + l.id">{{ l.numero }}</a></td><td>{{ l.article ?? '—' }}</td>
              <td class="r mono">{{ l.quantity ?? '—' }} {{ l.unit }}</td><td><span class="st" [class]="tone(l.qualityStatus)"><i></i>{{ label(l.qualityStatus) }}</span></td></tr>
          } @empty { <tr><td colspan="4" class="note">Aucun lot.</td></tr> }</tbody></table></div>
      </div>
      <form class="box pad sec" (ngSubmit)="quarantine()" aria-label="Mise en quarantaine">
        <label class="field"><span>Motif</span><input name="r" [(ngModel)]="reason" required /></label>
        <button type="submit" class="btn pri" [disabled]="busy() || !toBlock().length || !reason.trim()">
          Mettre ces lots en quarantaine pour réévaluation ({{ toBlock().length }})</button>
        @if (done() !== null) { <p class="note" role="status">{{ done() }} lot(s) mis en quarantaine.
          @if (ncId(); as nc) { <a class="lnk" [routerLink]="'/non-conformites/' + nc">NC liée</a> }</p> }
      </form>
    }
  `,
  styles: `.sum { margin-bottom: 16px; } .two { grid-template-columns: 1fr 1fr; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EffetInstrumentComponent {
  private readonly api = inject(InstrumentsService);
  protected readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly inst = signal<Instrument | null>(null);
  protected readonly impact = signal<Impact | null>(null);
  protected readonly toBlock = computed(() => (this.impact() ? lotsToQuarantine(this.impact()!) : []));
  protected readonly busy = signal(false);
  protected readonly done = signal<number | null>(null);
  protected readonly ncId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly date = formatDate;
  protected readonly dt = formatDateTime;
  protected readonly point = pointLabel;
  protected readonly tone = qualityTone;
  protected readonly label = statusLabel;
  protected reason = '';

  constructor() {
    void this.load();
  }
  private async load(): Promise<void> {
    try {
      const [i, im] = await Promise.all([this.api.get(this.id), this.api.impact(this.id)]);
      this.inst.set(i);
      this.impact.set(im);
      if (!this.reason) this.reason = `Instrument ${i.code} hors tolérance à l'étalonnage — réévaluation`;
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de calculer l'effet instrument."));
    }
  }
  async quarantine(): Promise<void> {
    this.busy.set(true);
    let n = 0;
    try {
      const r = await this.api.quarantine(this.id, this.reason.trim(), this.impact()?.since);
      n = r.quarantined.length;
      this.ncId.set(r.ncId);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'La mise en quarantaine a échoué pour un lot.'));
    } finally {
      this.done.set(n);
      this.busy.set(false);
      await this.load();
    }
  }
}
