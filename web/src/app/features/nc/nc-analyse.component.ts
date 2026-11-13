import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { RecordData } from '../../core/api.models';
import { Analyse, ISHIKAWA_KEYS, ISHIKAWA_LABELS, IshikawaKey, readAnalyse, writeAnalyse } from './analyse.model';

/** Onglet Analyse : 5 pourquoi + cause racine + 6M (Ishikawa). Présentation pure. */
@Component({
  selector: 'eq-nc-analyse',
  standalone: true,
  template: `
    <div class="two">
      <div class="box pad">
        <h3 class="t">5 pourquoi</h3>
        <ol class="why">
          @for (w of draft().analyse5p; track $index; let i = $index) {
            <li>
              <label [for]="'why-' + i">Pourquoi {{ i + 1 }}</label>
              <input [id]="'why-' + i" [value]="w" [disabled]="readonly()" (input)="setWhy(i, $any($event.target).value)" />
            </li>
          }
        </ol>
        <label class="field root"><span>Cause racine</span>
          <textarea rows="2" [value]="draft().causeRacine" [disabled]="readonly()" (input)="setRoot($any($event.target).value)"></textarea>
        </label>
      </div>
      <div>
        <h3 class="t">Diagramme 6M</h3>
        <div class="isk">
          @for (k of keys; track k) {
            <label><b>{{ labels[k] }}</b>
              <input [value]="draft().ishikawa[k]" [disabled]="readonly()" (input)="setIsk(k, $any($event.target).value)" />
            </label>
          }
        </div>
        @if (!readonly()) {
          <button type="button" class="btn save" [disabled]="saving()" (click)="save()">Enregistrer l'analyse</button>
        }
      </div>
    </div>
  `,
  styles: `
    .t { margin: 0 0 10px; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink2); }
    .why { padding: 0; margin: 0 0 12px; }
    .why li { list-style: none; border-left: 3px solid var(--line2); padding: 4px 0 4px 14px; margin-bottom: 6px; }
    .why label { display: block; font-size: 12px; font-weight: 700; color: var(--ink2); text-transform: uppercase; letter-spacing: .06em; }
    input, textarea { width: 100%; border: var(--bw) solid var(--line2); background: var(--surf); padding: 7px 9px; }
    .root { border-left: 3px solid var(--acc); padding-left: 14px; }
    .root span { color: var(--acc); }
    .isk { display: grid; grid-template-columns: repeat(2, 1fr); border: var(--bw) solid var(--line); background: var(--surf); }
    .isk label { padding: 10px 12px; border-right: var(--bw) solid var(--line); border-bottom: var(--bw) solid var(--line); font-size: 14px; }
    .isk b { display: block; font-size: 12px; text-transform: uppercase; color: var(--ink2); letter-spacing: .06em; margin-bottom: 4px; }
    .save { margin-top: 14px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NcAnalyseComponent {
  readonly data = input<RecordData>({});
  readonly readonly = input(false);
  readonly saving = input(false);
  readonly saved = output<RecordData>();

  protected readonly keys = ISHIKAWA_KEYS;
  protected readonly labels = ISHIKAWA_LABELS;
  protected readonly draft = signal<Analyse>(readAnalyse({}));

  constructor() {
    // Angular 18 : pas de linkedSignal ; le brouillon se réinitialise quand la fiche est rechargée.
    effect(() => this.draft.set(readAnalyse(this.data())));
  }

  protected setWhy(i: number, v: string): void {
    this.draft.update((d) => ({ ...d, analyse5p: d.analyse5p.map((w, j) => (j === i ? v : w)) }));
  }

  protected setRoot(v: string): void {
    this.draft.update((d) => ({ ...d, causeRacine: v }));
  }

  protected setIsk(k: IshikawaKey, v: string): void {
    this.draft.update((d) => ({ ...d, ishikawa: { ...d.ishikawa, [k]: v } }));
  }

  save(): void {
    this.saved.emit(writeAnalyse(this.data(), this.draft()));
  }
}
