import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/api-error';
import { RiskScale, validateScale } from '../../core/m4.models';
import { RisksService } from '../../core/m4.service';

/** Paramétrage → Échelles de risque : dimensions, niveaux, formule (produit) et seuils de criticité colorés. */
@Component({
  selector: 'eq-echelles-risque',
  imports: [FormsModule],
  template: `
    <div class="ph"><div><span class="ref">Paramétrage</span><h2>Échelles de risque</h2></div></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (saved()) { <p class="note" role="status">Échelle enregistrée.</p> }
    @if (scale(); as s) {
      <form class="box pad" (ngSubmit)="save(s)" aria-label="Échelle de risque">
        <div class="row"><label class="field"><span>Code</span><input name="code" [(ngModel)]="s.code" /></label>
          <label class="field"><span>Libellé</span><input name="lbl" [(ngModel)]="s.label" /></label></div>
        <h3>Dimensions</h3>
        @for (d of s.dimensions; track $index; let k = $index) {
          <div class="dim box pad">
            <div class="row"><label class="field"><span>Code dimension</span><input [name]="'dc' + k" [(ngModel)]="d.code" /></label>
              <label class="field"><span>Libellé dimension</span><input [name]="'dl' + k" [(ngModel)]="d.label" /></label>
              <label class="chk"><input type="checkbox" [name]="'df' + k" [ngModel]="s.formula.includes(d.code)" (ngModelChange)="toggleFormula(s, d.code)" /> Retenue dans la formule</label></div>
            <p class="note">Niveaux : @for (l of d.levels; track $index; let j = $index) {
              <input class="lv" [name]="'l' + k + '-' + j" [(ngModel)]="l.label" [attr.aria-label]="d.code + ' niveau ' + l.value" /> }
              <button type="button" class="btn" (click)="d.levels.push({ value: d.levels.length + 1, label: '' })">+ Niveau</button></p>
          </div>
        }
        <button type="button" class="btn" (click)="s.dimensions.push({ code: '', label: '', levels: [{ value: 1, label: '' }, { value: 2, label: '' }] })">+ Dimension</button>
        <p class="note">Formule : {{ s.formula.join(' × ') || '—' }}</p>
        <h3>Seuils de criticité</h3>
        <table aria-label="Seuils"><thead><tr><th>Niveau</th><th>Libellé</th><th>Min</th><th>Max</th><th>Couleur</th></tr></thead><tbody>
          @for (t of s.thresholds; track $index; let k = $index) {
            <tr><td><input [name]="'tl' + k" [(ngModel)]="t.level" /></td><td><input [name]="'tb' + k" [(ngModel)]="t.label" /></td>
              <td><input type="number" [name]="'tn' + k" [(ngModel)]="t.min" /></td><td><input type="number" [name]="'tx' + k" [(ngModel)]="t.max" /></td>
              <td><input type="color" [name]="'tc' + k" [(ngModel)]="t.color" [attr.aria-label]="'Couleur ' + t.label" /></td></tr>
          }</tbody></table>
        <button type="button" class="btn" (click)="s.thresholds.push({ level: '', label: '', min: 0, max: null, color: '#cccccc' })">+ Seuil</button>
        @for (e of errors(); track e) { <p class="err">{{ e }}</p> }
        <p><button type="submit" class="btn pri" [disabled]="busy()">Enregistrer l'échelle</button></p>
      </form>
    }
  `,
  styles: `h3 { font-size: 15px; margin: 16px 0 8px; } .row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0 12px; align-items: end; }
    .dim { margin-bottom: 10px; } .lv { width: 130px; margin-right: 6px; } .chk { margin-bottom: 14px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EchellesRisqueComponent {
  private readonly api = inject(RisksService);
  protected readonly scale = signal<RiskScale | null>(null);
  protected readonly errors = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.scales().then((s) => this.scale.set(s[0] ?? { id: null, code: 'STD', label: '', dimensions: [], formula: [], thresholds: [] }),
      (e) => this.error.set(errorMessage(e, 'Échelles indisponibles.')));
  }
  protected toggleFormula(s: RiskScale, code: string): void {
    s.formula = s.formula.includes(code) ? s.formula.filter((c) => c !== code) : [...s.formula, code];
  }
  async save(s: RiskScale): Promise<void> {
    const errs = validateScale(s);
    this.errors.set(errs);
    if (errs.length) return;
    this.busy.set(true);
    try {
      this.scale.set(await this.api.saveScale(s));
      this.saved.set(true);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "L'échelle n'a pas pu être enregistrée."));
    } finally {
      this.busy.set(false);
    }
  }
}
