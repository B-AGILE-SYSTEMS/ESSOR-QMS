import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/api-error';
import {
  ControlPlan, FLOW_POINTS, NQA_VALUES, emptyCharacteristic, emptyPlan, pointLabel, samplingLabel, validatePlan,
} from '../../core/m3b.models';
import { ControlPlansService } from '../../core/m3b.service';

/** Paramétrage → Plans de contrôle : liste versionnée et éditeur de caractéristiques. */
@Component({
  selector: 'eq-plans-controle',
  imports: [FormsModule],
  template: `
    <div class="ph"><div><span class="ref">Paramétrage</span><h2>Plans de contrôle</h2></div>
      <button type="button" class="btn pri" (click)="edit(null)">+ Nouveau plan</button></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (ok(); as msg) { <p class="ok-msg" role="status">{{ msg }}</p> }
    <div class="grid">
      <div class="box tw"><table><thead><tr><th>Code</th><th>Point</th><th>Échantillonnage</th><th>v</th></tr></thead><tbody>
        @for (p of plans(); track p.id) {
          <tr class="go" [class.sel]="draft()?.id === p.id" (click)="edit(p)"><td class="mono"><b>{{ p.code }}</b><br /><span class="note">{{ p.libelle }}</span></td>
            <td>{{ point(p.point) }}</td><td class="note">{{ sampling(p.sampling) }}</td><td class="mono">{{ p.version ?? 1 }}</td></tr>
        } @empty { <tr><td colspan="4" class="note">Aucun plan.</td></tr> }</tbody></table></div>

      @if (draft(); as d) {
        <form class="box pad" (ngSubmit)="save()" aria-label="Plan de contrôle">
          <div class="row">
            <label class="field"><span>Code</span><input name="code" [(ngModel)]="d.code" /></label>
            <label class="field"><span>Libellé</span><input name="lib" [(ngModel)]="d.libelle" /></label>
            <label class="field"><span>Point de flux</span><select name="pt" [(ngModel)]="d.point">
              @for (f of points; track f.code) { <option [value]="f.code">{{ f.label }}</option> }</select></label>
            <label class="field"><span>Cible</span><select name="ct" [(ngModel)]="d.target.type">
              <option value="article">Article</option><option value="famille">Famille</option>
              <option value="fournisseur">Fournisseur</option><option value="client">Client</option></select></label>
            <label class="field"><span>Valeur cible (code)</span><input name="cv" [(ngModel)]="d.target.value" /></label>
            <label class="field"><span>Échantillonnage</span><select name="sm" [(ngModel)]="d.sampling.mode">
              <option value="iso2859">ISO 2859-1 (niveau II, simple, normal)</option><option value="fixe">Taille fixe</option></select></label>
            @if (d.sampling.mode === 'fixe') {
              <label class="field"><span>Taille</span><input name="tl" type="number" min="1" [(ngModel)]="d.sampling.taille" /></label>
            } @else {
              <label class="field"><span>NQA</span><select name="nqa" [(ngModel)]="d.sampling.nqa">
                @for (n of nqa; track n) { <option [ngValue]="n">{{ n }}</option> }</select></label>
            }
            @if (d.point === 'PRODUCTION_ENCOURS') {
              <label class="field"><span>Fréquence : toutes les N pièces</span><input name="fp" type="number" min="1" [(ngModel)]="freq.pieces" /></label>
              <label class="field"><span>ou toutes les X min</span><input name="fm" type="number" min="1" [(ngModel)]="freq.minutes" /></label>
            }
          </div>
          <h3 class="t">Caractéristiques</h3>
          <div class="tw"><table class="chars"><thead><tr><th>Code</th><th>Libellé</th><th>Type</th><th>Nominal</th><th>Tol. min</th><th>Tol. max</th>
            <th>Unité</th><th>Instr.</th><th>Criticité</th><th></th></tr></thead><tbody>
            @for (c of d.characteristics; track $index; let i = $index) {
              <tr><td><input [name]="'cc' + i" [attr.aria-label]="'Code caractéristique ' + (i + 1)" [(ngModel)]="c.code" /></td>
                <td><input [name]="'cl' + i" [attr.aria-label]="'Libellé caractéristique ' + (i + 1)" [(ngModel)]="c.libelle" /></td>
                <td><select [name]="'ty' + i" [(ngModel)]="c.type"><option value="mesure">mesure</option><option value="attribut">attribut</option>
                  <option value="document">document</option></select></td>
                <td><input [name]="'no' + i" type="number" step="any" [(ngModel)]="c.nominal" [disabled]="c.type !== 'mesure'" /></td>
                <td><input [name]="'mi' + i" type="number" step="any" [attr.aria-label]="'Tolérance min ' + (i + 1)" [(ngModel)]="c.tolMin" [disabled]="c.type !== 'mesure'" /></td>
                <td><input [name]="'ma' + i" type="number" step="any" [attr.aria-label]="'Tolérance max ' + (i + 1)" [(ngModel)]="c.tolMax" [disabled]="c.type !== 'mesure'" /></td>
                <td><input [name]="'un' + i" [(ngModel)]="c.unite" /></td>
                <td><input [name]="'ir' + i" type="checkbox" [(ngModel)]="c.instrumentRequis" aria-label="Instrument requis" /></td>
                <td><select [name]="'cr' + i" [(ngModel)]="c.criticite"><option value="critique">critique</option><option value="majeure">majeure</option>
                  <option value="mineure">mineure</option></select></td>
                <td><button type="button" class="btn" [attr.aria-label]="'Retirer la caractéristique ' + (i + 1)" (click)="remove(i)">✕</button></td></tr>
            }</tbody></table></div>
          <div class="acts"><button type="button" class="btn" (click)="add()">+ Caractéristique</button>
            <button type="submit" class="btn pri" [disabled]="busy()">{{ d.id ? 'Enregistrer (nouvelle version)' : 'Créer le plan' }}</button></div>
          @if (issues().length) { <ul class="err">@for (m of issues(); track m) { <li>{{ m }}</li> }</ul> }
        </form>
      }
    </div>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: 1fr 1.8fr; gap: 18px; align-items: start; }
    .row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0 12px; }
    .t { font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink2); }
    .chars input:not([type='checkbox']), .chars select { width: 100%; min-width: 60px; border: var(--bw) solid var(--line2); padding: 6px; background: var(--surf); }
    .chars td { padding: 4px; } tr.sel td { background: var(--surf2); }
    .acts { display: flex; gap: 10px; margin-top: 12px; } ul.err { margin-top: 12px; padding-left: 28px; }
    @media (max-width: 1100px) { .grid, .row { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlansControleComponent {
  private readonly api = inject(ControlPlansService);
  protected readonly points = FLOW_POINTS;
  protected readonly nqa = NQA_VALUES;
  protected readonly point = pointLabel;
  protected readonly sampling = samplingLabel;
  protected readonly plans = signal<ControlPlan[]>([]);
  protected readonly draft = signal<ControlPlan | null>(null);
  protected readonly issues = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly ok = signal<string | null>(null);
  protected freq: { pieces: number | null; minutes: number | null } = { pieces: null, minutes: null };

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.plans.set(await this.api.list());
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les plans.'));
    }
  }

  /** Édite une copie profonde : la liste n'est modifiée qu'après enregistrement. */
  protected edit(p: ControlPlan | null): void {
    const copy: ControlPlan = p ? structuredClone(p) : emptyPlan();
    this.freq = { pieces: copy.frequency?.pieces ?? null, minutes: copy.frequency?.minutes ?? null };
    this.issues.set([]);
    this.ok.set(null);
    this.draft.set(copy);
  }

  protected add(): void {
    this.draft.update((d) => (d ? { ...d, characteristics: [...d.characteristics, emptyCharacteristic()] } : d));
  }

  protected remove(i: number): void {
    this.draft.update((d) => (d ? { ...d, characteristics: d.characteristics.filter((_, k) => k !== i) } : d));
  }

  async save(): Promise<void> {
    const d = this.draft();
    if (!d) return;
    const plan: ControlPlan = { ...d, frequency: d.point === 'PRODUCTION_ENCOURS' ? { ...this.freq } : null };
    const errs = validatePlan(plan);
    this.issues.set(errs);
    if (errs.length) return;
    this.busy.set(true);
    try {
      const saved = await this.api.save(plan);
      this.ok.set(`Plan ${saved.code} enregistré (version ${saved.version ?? 1}).`);
      this.draft.set(null);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e, "Le plan n'a pas pu être enregistré."));
    } finally {
      this.busy.set(false);
    }
  }
}
