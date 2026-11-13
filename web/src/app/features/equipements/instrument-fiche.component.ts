import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { formatDate } from '../../core/labels';
import { fmt, refLabel } from '../../core/m3b.models';
import { INSTRUMENT_STATUS, Instrument, NewCalibration, dueLabel, instrumentTone, previewVerdict } from '../../core/m4.models';
import { InstrumentsService } from '../../core/m4.service';

/** Fiche instrument : caractéristiques, historique d'étalonnage, saisie d'un étalonnage avec verdict, accès à l'effet instrument. */
@Component({
  selector: 'eq-instrument-fiche',
  imports: [FormsModule, RouterLink],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (inst(); as i) {
      <div class="ph"><div><span class="ref mono">{{ i.code }} · {{ ref(i.site) }} · {{ i.location ?? '' }}</span><h2>{{ i.designation }}</h2></div>
        <span class="st" data-testid="instrument-status" [class]="tone(i.status)"><i></i>{{ statusLabel(i.status) }}</span></div>
      @if (i.status === 'bloque') {
        <div class="box pad alert" role="status"><b>Instrument bloqué.</b> Il ne peut plus être choisi dans un contrôle.
          <a class="btn pri" [routerLink]="'/equipements/' + i.id + '/impact'">Voir l'effet instrument →</a></div>
      }
      <div class="two">
        <div class="box pad"><dl class="kv">
          <dt>Type</dt><dd>{{ i.type ?? '—' }}</dd><dt>N° de série</dt><dd class="mono">{{ i.serial ?? '—' }}</dd>
          <dt>Plage</dt><dd class="mono">{{ i.range ?? '—' }}</dd><dt>Résolution</dt><dd class="mono">{{ i.resolution ?? '—' }}</dd>
          <dt>EMT</dt><dd class="mono">± {{ fmt(i.emt) }}</dd><dt>Périodicité</dt><dd class="mono">{{ i.periodicityMonths ?? '—' }} mois</dd>
          <dt>Prestataire</dt><dd>{{ i.provider ?? '—' }}</dd>
          <dt>Échéance</dt><dd class="mono">{{ date(i.nextDue) }} <span class="st" [class]="due(i.nextDue).tone"><i></i>{{ due(i.nextDue).text }}</span></dd>
          <dt>QR</dt><dd class="mono">{{ i.qr }}</dd>
        </dl></div>
        <form class="box pad" (ngSubmit)="save()" aria-label="Saisie d'étalonnage">
          <h3>Nouvel étalonnage</h3>
          <div class="row"><label class="field"><span>Date</span><input type="date" name="d" [(ngModel)]="draft.date" required /></label>
            <label class="field"><span>Prestataire</span><input name="p" [(ngModel)]="draft.provider" [disabled]="draft.internal" /></label></div>
          <label class="chk"><input type="checkbox" name="int" [(ngModel)]="draft.internal" /> Étalonnage interne</label>
          <label class="field"><span>N° de certificat</span><input name="cn" [(ngModel)]="draft.certificateNumber" /></label>
          <table class="pts" aria-label="Points de mesure"><thead><tr><th>Nominal</th><th>Mesuré</th><th class="r">Écart</th><th></th></tr></thead>
            <tbody>@for (p of draft.points; track $index; let k = $index) {
              <tr><td><input type="number" step="any" [name]="'n' + k" [attr.aria-label]="'Nominal ' + (k + 1)" [(ngModel)]="p.nominal" /></td>
                <td><input type="number" step="any" [name]="'m' + k" [attr.aria-label]="'Mesuré ' + (k + 1)" [(ngModel)]="p.mesure" /></td>
                <td class="r mono" [class.ko]="isOut(p)">{{ gap(p) }}</td>
                <td><button type="button" class="btn" (click)="draft.points.splice(k, 1)" [attr.aria-label]="'Retirer le point ' + (k + 1)">×</button></td></tr>
            }</tbody></table>
          <button type="button" class="btn" (click)="draft.points.push({ nominal: null, mesure: null })">+ Point</button>
          <p class="verdict" data-testid="verdict-preview">Verdict prévisionnel (EMT ± {{ fmt(i.emt) }}) :
            <b [class]="verdict() === 'non_conforme' ? 'ko' : 'okc'">{{ verdict() === null ? '—' : verdict() === 'conforme' ? 'Conforme' : 'Non conforme' }}</b></p>
          <button type="submit" class="btn pri" [disabled]="busy() || !draft.date">Enregistrer l'étalonnage</button>
        </form>
      </div>
      <div class="box tw sec"><table aria-label="Historique d'étalonnage">
        <thead><tr><th>Date</th><th>Prestataire</th><th>Points (nominal → mesuré, écart)</th><th>Verdict</th><th>Certificat</th><th>NC</th><th>Prochaine échéance</th></tr></thead>
        <tbody>@for (c of i.calibrations; track c.id) {
          <tr><td class="mono">{{ date(c.date) }}</td><td>{{ c.internal ? 'Interne' : (c.provider ?? '—') }}</td>
            <td class="mono">@for (p of c.points; track $index) { {{ fmt(p.nominal) }} → {{ fmt(p.mesure) }} ({{ fmt(p.ecart) }}) ; }</td>
            <td><span class="st" [class]="c.verdict === 'conforme' ? 'ok' : 'crit'"><i></i>{{ c.verdict === 'conforme' ? 'Conforme' : 'Non conforme' }}</span></td>
            <td>@if (c.certificateUrl) { <a class="lnk" [href]="c.certificateUrl" target="_blank" rel="noopener">{{ c.certificateNumber ?? c.certificate }}</a> } @else { {{ c.certificateNumber ?? '—' }} }</td>
            <td>@if (c.ncId) { <a class="lnk mono" [routerLink]="'/non-conformites/' + c.ncId">{{ c.ncReference ?? 'NC' }}</a> } @else { — }</td>
            <td class="mono">{{ date(c.nextDue) }}</td></tr>
        } @empty { <tr><td colspan="7" class="note">Aucun étalonnage.</td></tr> }</tbody></table></div>
    }
  `,
  styles: `
    h3 { margin: 0 0 10px; font-size: 15px; } .row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
    .alert { border-color: var(--crit); margin-bottom: 16px; display: flex; gap: 16px; align-items: center; justify-content: space-between; }
    .pts { width: 100%; margin: 8px 0; input { width: 110px; } } .ko { color: var(--crit); font-weight: 600; } .okc { color: var(--ok); }
    .chk { display: block; margin-bottom: 8px; } .verdict { margin: 12px 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InstrumentFicheComponent {
  private readonly api = inject(InstrumentsService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  protected readonly inst = signal<Instrument | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly ref = refLabel;
  protected readonly date = formatDate;
  protected readonly fmt = fmt;
  protected readonly tone = instrumentTone;
  protected readonly due = (d: string | null) => dueLabel(d);
  protected draft: NewCalibration = this.blank();

  constructor() {
    void this.load();
  }
  private blank(): NewCalibration {
    return { date: new Date().toISOString().slice(0, 10), provider: '', internal: false, certificateNumber: null, comment: null,
      points: [{ nominal: null, mesure: null }, { nominal: null, mesure: null }] };
  }
  private async load(): Promise<void> {
    try {
      const i = await this.api.get(this.id);
      this.inst.set(i);
      if (!this.draft.provider) this.draft.provider = i.provider ?? '';
    } catch (e) {
      this.error.set(errorMessage(e, 'Instrument introuvable.'));
    }
  }
  protected verdict(): 'conforme' | 'non_conforme' | null { return previewVerdict(this.draft.points, this.inst()?.emt ?? null); }
  protected gap(p: { nominal: number | null; mesure: number | null }): string {
    return p.nominal === null || p.mesure === null ? '—' : fmt(p.mesure - p.nominal, 4);
  }
  protected isOut(p: { nominal: number | null; mesure: number | null }): boolean {
    return previewVerdict([p], this.inst()?.emt ?? null) === 'non_conforme';
  }
  protected statusLabel(s: string): string { return INSTRUMENT_STATUS[s] ?? s; }

  async save(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.calibrate(this.id, this.draft);
      this.draft = this.blank();
      this.error.set(null);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e, "L'étalonnage n'a pas pu être enregistré."));
    } finally {
      this.busy.set(false);
    }
  }
}
