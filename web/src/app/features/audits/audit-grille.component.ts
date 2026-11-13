import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { QmsRecord } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import {
  ANSWER_LABELS, ANSWER_RESULTS, AnswerResult, AuditQuestion, Standard, conformityByChapter, isGap, standardLabel,
} from '../../core/m3.models';
import { AuditsService, StandardsService, auditReportUrl } from '../../core/m3.service';

interface Draft { result: AnswerResult | null; comment: string; evidence: string; question: string; closed: boolean }

/** Onglet « Grille » de la fiche audit : génération depuis le référentiel, édition, exécution, constats, NC créées, rapport. */
@Component({
  selector: 'eq-audit-grille',
  imports: [FormsModule, RouterLink],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (info(); as msg) { <p class="ok-msg" role="status">{{ msg }}</p> }
    @if (status() === 'PLANIFIE') {
      <form class="box pad gen" (ngSubmit)="generate()">
        <span class="lab">Générer la grille depuis :</span>
        @for (s of standards(); track s.id) {
          <label class="ck"><input type="checkbox" [checked]="chosen().has(s.id)" (change)="choose(s.id)" /> {{ stdLabel(s) }}</label>
        }
        <button type="submit" class="btn" [disabled]="busy() || !chosen().size">{{ questions().length ? 'Régénérer la grille' : 'Générer la grille' }}</button>
      </form>
    }
    @if (questions().length) {
      <div class="sum box pad">
        <div><b class="num">{{ answered() }}/{{ questions().length }}</b> <span class="note">questions répondues</span></div>
        <div><b class="num">{{ gaps().length }}</b> <span class="note">écart(s)</span></div>
        @for (c of chapters(); track c.chapter) {
          <div><span class="mono">§{{ c.chapter }}</span> <b class="num">{{ c.rate === null ? '—' : c.rate + ' %' }}</b></div>
        }
        @if (reportReady()) { <a class="btn pri rp" [href]="reportUrl()">Télécharger le rapport PDF</a> }
      </div>
    }
    <ol class="qs">
      @for (q of questions(); track q.id; let i = $index) {
        <li class="box q" [class.gap]="gap(q.result)">
          <div class="qh">
            <span class="mono n">{{ i + 1 }}. §{{ q.requirementCode ?? '—' }}</span>
            @if (status() === 'PLANIFIE') {
              <input class="qt" [attr.aria-label]="'Question ' + (i + 1)" [(ngModel)]="drafts[q.id].question" (blur)="saveQuestion(q)" />
              <button type="button" class="btn ghost" [attr.aria-label]="'Supprimer la question ' + (i + 1)" (click)="remove(q)">✕</button>
            } @else { <b>{{ q.question }}</b> }
          </div>
          @if (q.requirementTitle) { <p class="note">{{ q.requirementTitle }}</p> }
          <div class="chips" role="group" [attr.aria-label]="'Résultat question ' + (i + 1)">
            @for (r of results; track r) {
              <button type="button" class="chip" [class]="'chip r-' + r" [disabled]="!running() || busy()" [attr.aria-pressed]="drafts[q.id].result === r"
                (click)="drafts[q.id].result = r">{{ labels[r] }}</button>
            }
          </div>
          @if (running()) {
            <div class="two-in">
              <label class="field"><span>Commentaire</span><input [attr.aria-label]="'Commentaire question ' + (i + 1)" [(ngModel)]="drafts[q.id].comment" /></label>
              <label class="field"><span>Preuve</span><input [attr.aria-label]="'Preuve question ' + (i + 1)" [(ngModel)]="drafts[q.id].evidence" /></label>
            </div>
            <div class="act">
              <label class="ck"><input type="checkbox" [(ngModel)]="drafts[q.id].closed" [attr.aria-label]="'Clore la question ' + (i + 1)" /> Clore la question (un écart crée la NC aussitôt)</label>
              <button type="button" class="btn" [disabled]="busy() || !drafts[q.id].result" (click)="answer(q)">Enregistrer la réponse</button>
            </div>
          } @else if (q.comment || q.evidence) {
            <p class="note">{{ q.comment }}@if (q.evidence) { · preuve : {{ q.evidence }} }</p>
          }
          @if (q.ncId) { <p class="nc">NC créée : <a class="lnk" [routerLink]="'/non-conformites/' + q.ncId">{{ q.ncReference ?? q.ncId }}</a></p> }
        </li>
      } @empty {
        <li class="note">Aucune question. {{ status() === 'PLANIFIE' ? 'Générez la grille depuis un référentiel.' : '' }}</li>
      }
    </ol>
    @if (gaps().length || ncs().length) {
      <div class="sec two">
        <div><h3>Constats</h3>
          <div class="box tw"><table><thead><tr><th>Exigence</th><th>Résultat</th><th>Commentaire</th></tr></thead><tbody>
            @for (q of findings(); track q.id) { <tr><td class="mono">§{{ q.requirementCode }}</td><td>{{ labels[q.result!] }}</td><td>{{ q.comment || '—' }}</td></tr> }
          </tbody></table></div></div>
        <div><h3>NC créées</h3>
          <ul class="box pad">@for (q of ncs(); track q.id) { <li><a class="lnk" [routerLink]="'/non-conformites/' + q.ncId">{{ q.ncReference ?? q.ncId }}</a> — §{{ q.requirementCode }}</li> }
            @empty { <li class="note">Créées à la clôture de la question ou à la rédaction du rapport.</li> }</ul></div>
      </div>
    }
  `,
  styles: `
    .gen { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; .lab { font-weight: 700; } }
    .ck { display: inline-flex; gap: 6px; align-items: center; }
    .sum { display: flex; gap: 22px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; .rp { margin-left: auto; } b, .note { margin-left: 5px; } }
    .qs { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .q { padding: 12px 16px; border-left: 4px solid var(--line2); }
    .q.gap { border-left-color: var(--crit); }
    .qh { display: flex; gap: 10px; align-items: baseline; .n { color: var(--ink2); white-space: nowrap; } }
    .qt { flex: 1; border: var(--bw) solid var(--line2); padding: 6px 8px; background: var(--surf); }
    .chips { margin: 8px 0; }
    .chip.r-ecart_majeur[aria-pressed='true'], .chip.r-ecart_mineur[aria-pressed='true'] { background: var(--crit); border-color: var(--crit); }
    .chip.r-conforme[aria-pressed='true'] { background: var(--ok); border-color: var(--ok); }
    .two-in { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; .field { margin-bottom: 8px; } }
    .act { display: flex; gap: 14px; align-items: center; justify-content: space-between; }
    .ck { display: inline-flex; gap: 6px; align-items: center; font-size: 13px; }
    .nc { margin: 8px 0 0; font-weight: 600; color: var(--crit); }
    ul.box { margin: 0; padding-left: 30px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditGrilleComponent {
  protected readonly stdLabel = standardLabel;
  private readonly audits = inject(AuditsService);
  private readonly stds = inject(StandardsService);
  readonly record = input.required<QmsRecord>();
  /** La grille a changé : la fiche recharge ses transitions (« grille », « réponses »). */
  readonly changed = output<void>();

  protected readonly results = ANSWER_RESULTS;
  protected readonly labels = ANSWER_LABELS;
  protected readonly gap = isGap;
  protected readonly questions = signal<AuditQuestion[]>([]);
  protected readonly standards = signal<Standard[]>([]);
  protected readonly chosen = signal<Set<string>>(new Set());
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected drafts: Record<string, Draft> = {};

  protected readonly status = computed(() => this.record().status);
  protected readonly running = computed(() => this.status() === 'EN_COURS');
  protected readonly reportReady = computed(() => ['RAPPORT', 'CLOTURE'].includes(this.status()));
  protected readonly reportUrl = computed(() => auditReportUrl(this.record().id));
  protected readonly answered = computed(() => this.questions().filter((q) => !!q.result).length);
  protected readonly gaps = computed(() => this.questions().filter((q) => isGap(q.result)));
  protected readonly findings = computed(() => this.questions().filter((q) => q.result && q.result !== 'conforme' && q.result !== 'na'));
  protected readonly ncs = computed(() => this.questions().filter((q) => !!q.ncId));
  protected readonly chapters = computed(() => conformityByChapter(this.questions()));

  constructor() {
    effect(() => {
      const rec = this.record();
      untracked(() => void this.load(rec));
    });
  }

  private async load(rec: QmsRecord): Promise<void> {
    const ids = (rec.data['standardIds'] as string[] | undefined) ?? [];
    if (!this.chosen().size) this.chosen.set(new Set(ids));
    try {
      if (rec.status === 'PLANIFIE' && !this.standards().length) this.standards.set(await this.stds.list());
      this.setQuestions(await this.audits.checklist(rec.id));
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de charger la grille d'audit."));
    }
  }

  private setQuestions(qs: AuditQuestion[]): void {
    this.questions.set(qs);
    this.drafts = Object.fromEntries(qs.map((q) => [q.id, {
      result: q.result ?? null, comment: q.comment ?? '', evidence: q.evidence ?? '', question: q.question, closed: !!q.closed,
    }]));
  }

  protected choose(id: string): void {
    const next = new Set(this.chosen());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.chosen.set(next);
  }

  async generate(): Promise<void> {
    await this.run(async () => {
      const qs = await this.audits.generate(this.record().id, [...this.chosen()]);
      this.setQuestions(qs);
      this.info.set(`Grille générée : ${qs.length} question(s).`);
      this.changed.emit();
    });
  }

  async saveQuestion(q: AuditQuestion): Promise<void> {
    const text = this.drafts[q.id].question.trim();
    if (!text || text === q.question) return;
    await this.run(async () => this.replace(await this.audits.editQuestion(this.record().id, q.id, text)));
  }

  async remove(q: AuditQuestion): Promise<void> {
    await this.run(async () => {
      await this.audits.deleteQuestion(this.record().id, q.id);
      this.questions.update((qs) => qs.filter((x) => x.id !== q.id));
      this.changed.emit();
    });
  }

  async answer(q: AuditQuestion): Promise<void> {
    const d = this.drafts[q.id];
    if (!d.result) return;
    await this.run(async () => {
      const saved = await this.audits.answer(this.record().id, q.id, { result: d.result!, comment: d.comment.trim(), evidence: d.evidence.trim(), closed: d.closed });
      this.replace(saved);
      this.changed.emit();
      this.info.set(saved.ncId ? `Réponse enregistrée — NC ${saved.ncReference ?? ''} créée.` : 'Réponse enregistrée.');
    });
  }

  private replace(saved: AuditQuestion): void {
    this.questions.update((qs) => qs.map((x) => (x.id === saved.id ? { ...x, ...saved } : x)));
    this.drafts[saved.id] = { ...this.drafts[saved.id], question: saved.question ?? this.drafts[saved.id].question };
  }

  private async run(op: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.info.set(null);
    try {
      await op();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
