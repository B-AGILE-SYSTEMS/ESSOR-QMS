import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import {
  Characteristic, ControlPlan, DECISION_LABELS, Decision, Inspection, InspectionResult, measureDefects, measureOk, parseValues,
  pointLabel, proposeDecision, samplingLabel, statusLabel,
} from '../../core/m3b.models';
import { ControlPlansService, InspectionsService } from '../../core/m3b.service';
import { newIdempotencyKey } from '../../core/records.service';

/** Saisie d'une caractéristique : texte des mesures, nombre de défauts (attribut) ou présence (document). */
interface Entry { c: Characteristic; text: string; defects: number; present: boolean; instrument: string }

/** Exécution d'un contrôle : résultats guidés par le plan, verdict automatique, décision proposée. */
@Component({
  selector: 'eq-controle-saisie',
  imports: [FormsModule, RouterLink],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (insp(); as i) {
      <div class="ph"><div><span class="ref">{{ point(i.point) }} · plan {{ plan()?.code }} v{{ i.planVersion ?? plan()?.version ?? 1 }}</span>
          <h2>Contrôle du lot <a class="lnk mono" [routerLink]="'/lots/' + i.lotId">{{ i.lotNumero ?? i.lotId }}</a></h2></div>
        <div class="smp box pad"><b class="mono">n = {{ i.sampleSize }}</b>@if (i.codeLetter) { · lettre {{ i.codeLetter }} }
          @if (i.ac !== null) { · Ac {{ i.ac }} / Re {{ i.re }} }<br /><span class="note">{{ sampling() }}</span></div></div>

      <form (ngSubmit)="save()" aria-label="Résultats du contrôle">
        <div class="box tw"><table>
          <thead><tr><th>Caractéristique</th><th>Attendu</th><th>Saisie</th><th>Instrument</th><th>Verdict</th></tr></thead>
          <tbody>@for (e of entries(); track e.c.code) {
            <tr><td><b>{{ e.c.libelle }}</b><br /><span class="note mono">{{ e.c.code }} · {{ e.c.criticite }}</span></td>
              <td class="mono">@if (e.c.type === 'mesure') { {{ e.c.nominal ?? '—' }} [{{ e.c.tolMin ?? '−∞' }} ; {{ e.c.tolMax ?? '+∞' }}] {{ e.c.unite ?? '' }} }
                @else if (e.c.type === 'attribut') { 0 défaut } @else { Document présent }</td>
              <td>@switch (e.c.type) {
                @case ('mesure') { <input class="in" [name]="'v-' + e.c.code" [attr.aria-label]="'Mesures ' + e.c.libelle" [(ngModel)]="e.text"
                  (ngModelChange)="touch()" placeholder="12,01 12,02 …" [disabled]="closed()" /> }
                @case ('attribut') { <input class="in num" type="number" min="0" [name]="'d-' + e.c.code" [attr.aria-label]="'Défauts ' + e.c.libelle"
                  [(ngModel)]="e.defects" (ngModelChange)="touch()" [disabled]="closed()" /> }
                @default { <label><input type="checkbox" [name]="'p-' + e.c.code" [(ngModel)]="e.present" (ngModelChange)="touch()" [disabled]="closed()" /> présent</label> }
              }</td>
              <td><input class="in" [name]="'i-' + e.c.code" [attr.aria-label]="'Instrument ' + e.c.libelle" [(ngModel)]="e.instrument"
                [required]="e.c.instrumentRequis" [disabled]="closed()" /></td>
              <td>@if (result(e); as r) { <span class="st" [class]="r.ok ? 'ok' : 'crit'"><i></i>{{ r.ok ? 'Conforme' : 'Non conforme' }}</span> }</td></tr>
          }</tbody></table></div>
        @if (!closed()) {
          <div class="acts"><button type="submit" class="btn" [disabled]="busy()">Enregistrer les résultats</button>
            @if (saved()) { <span class="note" role="status">Résultats enregistrés.</span> }</div>
        }
      </form>

      @if (closed()) {
        <p class="ok-msg" role="status">Contrôle terminé : décision « {{ label(i.decision) }} ».</p>
      } @else if (saved()) {
        <form class="nextbar" (ngSubmit)="decide()" aria-label="Décision">
          <p>Décision proposée : <b>{{ decisionLabel(proposed()) }}</b> ({{ defects() }} non-conforme(s)).</p>
          <div class="dec">
            <select name="dec" aria-label="Décision" [(ngModel)]="decision">
              @for (d of decisions(); track d[0]) { <option [value]="d[0]">{{ d[1] }}</option> }</select>
            <input name="com" aria-label="Commentaire" [(ngModel)]="comment" placeholder="Commentaire" />
            <button type="submit" class="btn pri" [disabled]="busy() || (decision === 'DEROGATION' && !comment.trim())">Valider la décision</button>
          </div>
        </form>
      }
    }
  `,
  styles: `
    .smp { text-align: right; } .in { width: 100%; border: var(--bw) solid var(--line2); padding: 8px; min-height: 38px; background: var(--surf); }
    .num { max-width: 110px; } .acts { display: flex; gap: 12px; align-items: center; margin-top: 14px; }
    .dec { display: flex; gap: 8px; flex-wrap: wrap; select, input { min-height: 40px; border: var(--bw) solid var(--line2); padding: 0 8px; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ControleSaisieComponent {
  readonly id = input.required<string>();
  private readonly api = inject(InspectionsService);
  private readonly plansApi = inject(ControlPlansService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly point = pointLabel;
  protected readonly label = statusLabel;
  protected readonly decisionLabel = (d: Decision) => DECISION_LABELS[d];
  protected readonly insp = signal<Inspection | null>(null);
  protected readonly plan = signal<ControlPlan | null>(null);
  protected readonly entries = signal<Entry[]>([]);
  private readonly version = signal(0);
  protected readonly saved = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected decision: Decision = 'LIBERE';
  protected comment = '';
  /** Une clé par lot de résultats : un renvoi après coupure ne duplique pas la saisie. */
  private key = newIdempotencyKey();

  protected readonly closed = computed(() => !!this.insp()?.decision);
  protected readonly sampling = computed(() => samplingLabel(this.plan()?.sampling));
  protected readonly results = computed(() => {
    this.version();
    return this.entries().map((e) => this.result(e));
  });
  protected readonly defects = computed(() => {
    this.version();
    return this.entries().reduce((n, e) => n + this.defectsOf(e), 0);
  });
  protected readonly proposed = computed(() => {
    const i = this.insp();
    return proposeDecision(this.entries().map((e) => e.c), this.results(), this.defects(), i?.ac, i?.re);
  });
  protected readonly decisions = computed(() =>
    Object.entries(DECISION_LABELS).filter(([k]) => k !== 'DEROGATION' || this.auth.hasRole('QUALITY_MANAGER')));

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => void this.load(id));
    });
  }

  private async load(id: string): Promise<void> {
    try {
      const [i, plans] = await Promise.all([this.api.get(id), this.plansApi.list()]);
      const plan = plans.find((p) => p.id === i.controlPlanId) ?? null;
      this.insp.set(i);
      this.plan.set(plan);
      this.saved.set(i.results.length > 0);
      this.entries.set((plan?.characteristics ?? []).map((c) => {
        const prev = i.results.find((r) => r.characteristic === c.code);
        return { c, text: c.type === 'mesure' ? (prev?.values ?? []).join(' ') : '', instrument: prev?.instrument ?? '',
          defects: c.type === 'attribut' ? Number(prev?.values?.[0] ?? 0) : 0, present: c.type === 'document' ? !!prev?.ok : false };
      }));
      this.decision = this.proposed();
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger le contrôle.'));
    }
  }

  protected touch(): void {
    this.saved.set(false);
    this.key = newIdempotencyKey();
    this.version.update((v) => v + 1);
  }

  private defectsOf(e: Entry): number {
    if (e.c.type === 'mesure') return measureDefects(e.c, parseValues(e.text));
    if (e.c.type === 'attribut') return Number(e.defects) || 0;
    return e.present ? 0 : 1;
  }

  protected result(e: Entry): InspectionResult {
    const instrument = e.instrument.trim() || null;
    if (e.c.type === 'mesure') {
      const values = parseValues(e.text);
      return { characteristic: e.c.code, values, ok: measureOk(e.c, values), instrument };
    }
    if (e.c.type === 'attribut') return { characteristic: e.c.code, values: [Number(e.defects) || 0], ok: !Number(e.defects), instrument };
    return { characteristic: e.c.code, values: [e.present ? 'present' : 'absent'], ok: e.present, instrument };
  }

  async save(): Promise<void> {
    const i = this.insp();
    if (!i) return;
    const missing = this.entries().filter((e) => (e.c.type === 'mesure' && !parseValues(e.text).length) || (e.c.instrumentRequis && !e.instrument.trim()));
    if (missing.length) {
      this.error.set(`Saisie incomplète : ${missing.map((e) => e.c.libelle).join(', ')}.`);
      return;
    }
    this.busy.set(true);
    try {
      await this.api.saveResults(i.id, this.results(), this.key);
      this.saved.set(true);
      this.decision = this.proposed();
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Les résultats n’ont pas pu être enregistrés.'));
    } finally {
      this.busy.set(false);
    }
  }

  async decide(): Promise<void> {
    const i = this.insp();
    if (!i) return;
    this.busy.set(true);
    try {
      await this.api.decide(i.id, this.decision, this.comment.trim());
      await this.router.navigateByUrl(`/lots/${i.lotId}`);
    } catch (e) {
      this.error.set(errorMessage(e, 'La décision n’a pas pu être enregistrée.'));
    } finally {
      this.busy.set(false);
    }
  }
}
