import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate, statusTone } from '../../core/labels';
import { AuditSummary, Standard, plannedDateOf, standardLabel } from '../../core/m3.models';
import { AuditsService, StandardsService } from '../../core/m3.service';
import { RecordsService } from '../../core/records.service';
import { UserRef } from '../../core/api.models';
import { UserPickerComponent } from '../nc/user-picker.component';

const MONTHS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre'];

/** Regroupe les audits par mois (0–11) de leur date prévue. */
export function byMonth(list: AuditSummary[]): AuditSummary[][] {
  const months: AuditSummary[][] = MONTHS.map(() => []);
  for (const a of list) {
    const d = plannedDateOf(a);
    const m = d ? Number(d.slice(5, 7)) - 1 : NaN;
    if (m >= 0 && m < 12) months[m].push(a);
  }
  return months.map((l) => [...l].sort((a, b) => (plannedDateOf(a) ?? '').localeCompare(plannedDateOf(b) ?? '')));
}

/** Audits : programme annuel en calendrier et planification d'un audit (fiche AUDIT). */
@Component({
  selector: 'eq-audits-programme',
  imports: [FormsModule, RouterLink, UserPickerComponent],
  template: `
    <div class="ph">
      <div><span class="ref">Terrain</span><h2>Programme d'audits {{ year() }}</h2></div>
      <div class="yr">
        <button type="button" class="btn" aria-label="Année précédente" (click)="setYear(year() - 1)">←</button>
        <button type="button" class="btn" aria-label="Année suivante" (click)="setYear(year() + 1)">→</button>
        <button type="button" class="chip" [attr.aria-pressed]="mine()" (click)="toggleMine()">Mes audits</button>
        <button type="button" class="btn pri" (click)="planning.set(!planning())">＋ Planifier un audit</button>
      </div>
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (planning()) {
      <form class="box pad plan" (ngSubmit)="plan()" aria-label="Planifier un audit">
        <label class="field"><span>Titre</span><input name="title" [(ngModel)]="draft.title" required /></label>
        <label class="field"><span>Site</span><select name="site" [(ngModel)]="draft.siteId" required>
          @for (s of auth.me()?.sites ?? []; track s.id) { <option [value]="s.id">{{ s.name }}</option> }</select></label>
        <label class="field"><span>Date prévue</span><input type="date" name="date" [(ngModel)]="draft.plannedDate" required /></label>
        <label class="field"><span>Périmètre</span><input name="perimetre" [(ngModel)]="draft.perimetre" /></label>
        <div class="field"><span>Auditeur(s) : {{ auditorNames() || '—' }}</span><eq-user-picker label="Auditeur(s)" (picked)="addAuditor($event)" /></div>
        <label class="field"><span>Audités</span><input name="audites" [(ngModel)]="draft.audites" /></label>
        <fieldset class="field stds"><legend>Référentiel(s)</legend>
          @for (s of standards(); track s.id) {
            <label><input type="checkbox" [checked]="draft.standardIds.includes(s.id)" (change)="toggleStd(s.id)" /> {{ stdLabel(s) }}</label>
          } @empty { <span class="note">Aucun référentiel importé.</span> }
        </fieldset>
        <button type="submit" class="btn pri" [disabled]="busy() || !draft.title.trim() || !draft.siteId || !draft.plannedDate">Planifier</button>
      </form>
    }
    <div class="cal" aria-label="Calendrier annuel">
      @for (list of months(); track $index; let m = $index) {
        <section class="box mo" [attr.aria-label]="monthName[m]">
          <h3>{{ monthName[m] }} <span class="note">{{ list.length || '' }}</span></h3>
          @for (a of list; track a.id) {
            <a class="au" [routerLink]="['/audits', a.id]">
              <span class="mono">{{ date(planned(a)) }} · {{ a.reference }}</span>
              <b>{{ a.title }}</b>
              <span class="note">{{ a.siteName ?? '—' }}</span>
              <span [class]="'st ' + tone(a.status)"><i></i>{{ a.statusLabel }}</span>
            </a>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .yr { display: flex; gap: 8px; align-items: center; }
    .plan { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0 14px; margin-bottom: 16px; align-items: end; }
    .stds { grid-column: 1 / -1; border: 0; padding: 0; display: flex; gap: 16px; flex-wrap: wrap; legend { font-size: 13px; font-weight: 700; margin-bottom: 5px; } }
    .cal { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
    .mo { padding: 10px 12px; min-height: 120px; h3 { margin: 0 0 8px; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; display: flex; justify-content: space-between; } }
    .au { display: grid; gap: 2px; padding: 8px; margin-bottom: 6px; border-left: 3px solid var(--acc); background: var(--surf2); color: inherit; text-decoration: none; font-size: 13px; }
    .au:hover b { text-decoration: underline; }
    @media (max-width: 860px) { .cal, .plan { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditsProgrammeComponent {
  protected readonly stdLabel = standardLabel;
  protected readonly auth = inject(AuthService);
  private readonly audits = inject(AuditsService);
  private readonly stds = inject(StandardsService);
  private readonly records = inject(RecordsService);
  private readonly router = inject(Router);

  protected readonly monthName = MONTHS;
  protected readonly date = formatDate;
  protected readonly tone = statusTone;
  protected readonly planned = plannedDateOf;
  protected readonly year = signal(new Date().getFullYear());
  protected readonly list = signal<AuditSummary[]>([]);
  protected readonly months = computed(() => byMonth(this.list()));
  protected readonly standards = signal<Standard[]>([]);
  protected readonly planning = signal(false);
  protected readonly mine = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected draft = { title: '', siteId: '', plannedDate: '', perimetre: '', audites: '', standardIds: [] as string[] };
  protected readonly auditors = signal<UserRef[]>([]);
  protected readonly auditorNames = computed(() => this.auditors().map((u) => u.displayName).join(', '));

  protected addAuditor(u: UserRef): void {
    if (!this.auditors().some((x) => x.id === u.id)) this.auditors.set([...this.auditors(), u]);
  }

  constructor() {
    void this.load();
    this.stds.list().then((s) => this.standards.set(s), () => undefined);
  }

  toggleMine(): void {
    this.mine.set(!this.mine());
    void this.load();
  }

  setYear(y: number): void {
    this.year.set(y);
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.list.set(await this.audits.program(this.year(), { mine: this.mine() }));
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de charger le programme d'audits."));
    }
  }

  protected toggleStd(id: string): void {
    const ids = this.draft.standardIds;
    this.draft = { ...this.draft, standardIds: ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id] };
  }

  async plan(): Promise<void> {
    const d = this.draft;
    this.busy.set(true);
    try {
      const rec = await this.records.create({
        type: 'AUDIT', siteId: d.siteId, title: d.title.trim(), description: d.perimetre.trim(),
        data: { datePrevue: d.plannedDate, perimetre: d.perimetre.trim(), auditeurs: this.auditors().map((u) => u.id), audites: d.audites.trim(), standardIds: d.standardIds },
      });
      await this.router.navigate(['/audits', rec.id]);
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de planifier l'audit."));
    } finally {
      this.busy.set(false);
    }
  }
}
