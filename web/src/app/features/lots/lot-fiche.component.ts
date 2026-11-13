import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate, formatDateTime } from '../../core/labels';
import {
  ControlPlan, INSPECTION_STATUS_LABELS, Inspection, LINK_LABELS, LinkKind, Lot, QUALITY_STATUS_LABELS, QualityStatus, TraceRow,
  flattenTrace, pointLabel, qualityTone, refLabel, statusLabel,
} from '../../core/m3b.models';
import { ControlPlansService, InspectionsService, LotsService } from '../../core/m3b.service';
import { RappelComponent } from './rappel.component';

/** Fiche lot : statut qualité et historique, contrôles, généalogie ascendante/descendante, simulation de rappel. */
@Component({
  selector: 'eq-lot-fiche',
  imports: [FormsModule, RouterLink, RappelComponent],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (lot(); as l) {
      <div class="ph"><div><span class="ref">{{ ref(l.article) }}@if (l.fournisseur) { · {{ ref(l.fournisseur) }} }</span>
          <h2>Lot <span class="mono">{{ l.numero }}</span></h2></div>
        <div class="hd"><span class="st big" [class]="tone(l.qualityStatus)" data-testid="lot-status"><i></i>{{ label(l.qualityStatus) }}</span>
          <button type="button" class="btn pri" (click)="recallOpen.set(true)">Simuler un rappel</button></div></div>

      @if (recallOpen()) { <eq-rappel [lot]="l" (closed)="recallOpen.set(false)" (blocked)="reload()" /> }

      <div class="two">
        <div>
          <div class="sec"><h3>Généalogie
            <span class="chips" role="group" aria-label="Sens de la généalogie">
              <button type="button" class="chip" [attr.aria-pressed]="direction() === 'down'" (click)="loadTrace('down')">Descendante (où est-il allé ?)</button>
              <button type="button" class="chip" [attr.aria-pressed]="direction() === 'up'" (click)="loadTrace('up')">Ascendante (d'où vient-il ?)</button>
            </span></h3>
            <ul class="box tree" role="tree" aria-label="Généalogie du lot">
              @for (r of trace(); track r.key) {
                <li role="treeitem" [attr.aria-selected]="r.depth === 0" [attr.aria-level]="r.depth + 1" [style.padding-left.px]="12 + r.depth * 26" [class.root]="r.depth === 0">
                  @if (r.link) { <span class="kind">{{ linkLabel(r.link.kind) }}</span> }
                  @if (r.lot) {
                    <a class="lnk mono" [routerLink]="'/lots/' + r.lot.id">{{ r.lot.numero }}</a> <span>{{ ref(r.lot.article) }}</span>
                    <span class="st" [class]="tone(r.lot.qualityStatus)"><i></i>{{ label(r.lot.qualityStatus) }}</span>
                  } @else if (r.link?.partner) { <b>{{ ref(r.link!.partner) }}</b> }
                  @if (r.link) { <span class="note mono">{{ r.link.quantity ?? '' }} {{ r.link.reference ?? '' }}</span> }
                </li>
              } @empty { <li class="note pad">Aucun lien de généalogie.</li> }
            </ul>
          </div>

          <div class="sec"><h3>Contrôles
            @if (plans().length) {
              <span class="launch"><select aria-label="Plan de contrôle" [(ngModel)]="planId">
                @for (p of plans(); track p.id) { <option [value]="p.id">{{ p.code }} — {{ point(p.point) }}</option> }</select>
                <button type="button" class="btn" [disabled]="!planId || busy()" (click)="launch()">Lancer un contrôle</button></span>
            }</h3>
            <div class="box tw"><table><thead><tr><th>Point</th><th>Échantillon</th><th>Statut</th><th>Décision</th><th></th></tr></thead><tbody>
              @for (i of inspections(); track i.id) {
                <tr><td>{{ point(i.point) }}</td><td class="mono">{{ i.sampleSize }}</td><td>{{ insp(i.status) }}</td><td>{{ i.decision ? label(i.decision) : '—' }}</td>
                  <td class="r"><a class="lnk" [routerLink]="'/controles/' + i.id">Ouvrir</a></td></tr>
              } @empty { <tr><td colspan="5" class="note">Aucun contrôle.</td></tr> }
            </tbody></table></div>
          </div>
        </div>

        <aside>
          <div class="box pad"><dl class="kv">
            <dt>Article</dt><dd>{{ ref(l.article) }}</dd>
            <dt>Fournisseur</dt><dd>{{ ref(l.fournisseur) }}</dd>
            <dt>Lot fournisseur</dt><dd class="mono">{{ l.lotFournisseur ?? '—' }}</dd>
            <dt>Quantité</dt><dd class="mono">{{ l.quantite ?? '—' }} {{ l.unite }}</dd>
            <dt>DLC</dt><dd class="mono">{{ date(l.dlc) }}</dd>
            <dt>Site</dt><dd>{{ ref(l.site) }}</dd>
          </dl></div>

          <form class="box pad sec" (ngSubmit)="changeStatus()" aria-label="Changer le statut qualité">
            <label class="field"><span>Nouveau statut</span><select name="ns" [(ngModel)]="newStatus">
              @for (s of statusOptions(); track s[0]) { <option [value]="s[0]">{{ s[1] }}</option> }</select></label>
            <label class="field"><span>Motif</span><input name="motif" [(ngModel)]="reason" /></label>
            @if (newStatus === 'DEROGATION' && !reason.trim()) { <p class="note">La dérogation exige un motif.</p> }
            <button type="submit" class="btn" [disabled]="busy() || !canSubmitStatus()">Changer le statut</button>
          </form>

          <div class="sec"><h3>Historique du statut</h3>
            <ol class="box hist">@for (h of l.historique; track $index) {
              <li><span class="mono note">{{ dt(h.at) }}</span> <b>{{ label(h.status) }}</b>@if (h.by) { · {{ h.by }} }
                @if (h.reason) { <br /><span class="note">{{ h.reason }}</span> }</li>
            } @empty { <li class="note">Aucun changement.</li> }</ol></div>
        </aside>
      </div>
    }
  `,
  styles: `
    .hd { display: flex; gap: 14px; align-items: center; } .big { font-size: 15px; }
    .tree { list-style: none; margin: 0; padding: 6px 0; }
    .tree li { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding-top: 7px; padding-bottom: 7px; border-bottom: var(--bw) solid var(--surf2);
      position: relative; }
    .tree li.root { background: var(--surf2); box-shadow: inset 3px 0 0 var(--acc); }
    .kind { font: 600 11px/1 var(--mono); text-transform: uppercase; color: var(--ink2); }
    .launch { display: flex; gap: 8px; text-transform: none; letter-spacing: 0; select { min-height: 40px; } }
    .hist { list-style: none; margin: 0; padding: 0; li { padding: 9px 14px; border-bottom: var(--bw) solid var(--line); font-size: 14px; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LotFicheComponent {
  readonly id = input.required<string>();
  private readonly lots = inject(LotsService);
  private readonly plansApi = inject(ControlPlansService);
  private readonly inspApi = inject(InspectionsService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly ref = refLabel;
  protected readonly label = statusLabel;
  protected readonly tone = qualityTone;
  protected readonly point = pointLabel;
  protected readonly date = formatDate;
  protected readonly dt = formatDateTime;
  protected readonly insp = (s: string) => INSPECTION_STATUS_LABELS[s] ?? s;
  protected readonly linkLabel = (k: string) => LINK_LABELS[k as LinkKind] ?? k;

  protected readonly lot = signal<Lot | null>(null);
  protected readonly trace = signal<TraceRow[]>([]);
  protected readonly direction = signal<'up' | 'down'>('down');
  protected readonly inspections = signal<Inspection[]>([]);
  protected readonly plans = signal<ControlPlan[]>([]);
  protected readonly recallOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected planId = '';
  protected newStatus: QualityStatus = 'QUARANTAINE';
  protected reason = '';

  /** DEROGATION réservée au rôle QUALITY_MANAGER (contrat). */
  protected readonly statusOptions = computed(() =>
    Object.entries(QUALITY_STATUS_LABELS).filter(([k]) => k !== 'DEROGATION' || this.auth.hasRole('QUALITY_MANAGER')));

  constructor() {
    // Rechargement à chaque changement d'id (navigation d'un lot à l'autre depuis la généalogie).
    effect(() => {
      this.id();
      untracked(() => {
        this.recallOpen.set(false);
        void this.reload();
      });
    });
  }

  async reload(): Promise<void> {
    try {
      const l = await this.lots.get(this.id());
      this.lot.set(l);
      await Promise.all([this.loadTrace(this.direction()), this.loadInspections(), this.loadPlans()]);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger le lot.'));
    }
  }

  async loadTrace(dir: 'up' | 'down'): Promise<void> {
    this.direction.set(dir);
    try {
      this.trace.set(flattenTrace(await this.lots.trace(this.id(), dir)));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger la généalogie.'));
    }
  }

  private async loadInspections(): Promise<void> {
    this.inspections.set(await this.inspApi.list({ lotId: this.id() }));
  }

  private async loadPlans(): Promise<void> {
    const list = (await this.plansApi.list()).filter((p) => p.id);
    this.plans.set(list);
    if (!this.planId && list[0]?.id) this.planId = list[0].id;
  }

  protected canSubmitStatus(): boolean {
    return !!this.reason.trim() || this.newStatus !== 'DEROGATION';
  }

  async changeStatus(): Promise<void> {
    this.busy.set(true);
    try {
      this.lot.set(await this.lots.setStatus(this.id(), this.newStatus, this.reason.trim()));
      this.reason = '';
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Le statut n’a pas pu être changé.'));
    } finally {
      this.busy.set(false);
    }
  }

  async launch(): Promise<void> {
    const plan = this.plans().find((p) => p.id === this.planId);
    if (!plan?.id) return;
    this.busy.set(true);
    try {
      const i = await this.inspApi.create(this.id(), plan.id, plan.point);
      await this.router.navigateByUrl(`/controles/${i.id}`);
    } catch (e) {
      this.error.set(errorMessage(e, 'Le contrôle n’a pas pu être créé.'));
    } finally {
      this.busy.set(false);
    }
  }
}
