import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { Lot, Recall, formatUnitQty, lotsToBlock, qualityTone, refLabel, statusLabel } from '../../core/m3b.models';
import { LotsService, recallPdfUrl } from '../../core/m3b.service';

/**
 * Simulation de rappel depuis un lot : lots impactés, clients, quantités, dossier PDF et blocage du stock restant
 * (`POST /api/lots/{id}/recall/block`, après confirmation dans la page ; le serveur bloque et émet le webhook).
 */
@Component({
  selector: 'eq-rappel',
  imports: [RouterLink],
  template: `
    <section class="box rap" aria-labelledby="rap-t">
      <header><h3 id="rap-t">Simulation de rappel — lot <span class="mono">{{ lot().numero }}</span></h3>
        <button type="button" class="btn" (click)="closed.emit()">Fermer</button></header>
      @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
      @if (recall(); as r) {
        <div class="kpis">
          <div><span>Lots impactés</span><b class="mono">{{ r.lotsImpactes.length }}</b></div>
          <div><span>Clients touchés</span><b class="mono">{{ r.clients.length }}</b></div>
          <div><span>En stock</span><b class="mono">{{ qty(r.quantites.enStock) }}</b></div>
          <div><span>En cours</span><b class="mono">{{ qty(r.quantites.enCours) }}</b></div>
          <div><span>Expédié</span><b class="mono">{{ qty(r.quantites.expedie) }}</b></div>
        </div>
        <div class="cols">
          <div><h4>Lots impactés</h4><table><thead><tr><th>Lot</th><th>Article</th><th class="r">Qté</th><th class="r">Restant</th><th>Statut</th></tr></thead><tbody>
            @for (l of r.lotsImpactes; track l.id) {
              <tr><td><a class="lnk mono" [routerLink]="'/lots/' + l.id">{{ l.numero }}</a></td><td>{{ ref(l.article) }}</td>
                <td class="r mono">{{ l.quantite ?? '—' }} {{ l.unite }}</td><td class="r mono">{{ l.remaining ?? '—' }}</td><td><span class="st" [class]="tone(l.qualityStatus)"><i></i>{{ label(l.qualityStatus) }}</span></td></tr>
            } @empty { <tr><td colspan="5" class="note">Aucun.</td></tr> }</tbody></table></div>
          <div><h4>Clients touchés</h4><table><thead><tr><th>Client</th><th class="r">Qté</th><th>Références</th></tr></thead><tbody>
            @for (c of r.clients; track $index) {
              <tr><td><b>{{ ref(c.partner) }}</b></td><td class="r mono">{{ c.quantite }}</td><td class="mono note">{{ c.references.join(', ') }}</td></tr>
            } @empty { <tr><td colspan="3" class="note">Aucun client livré.</td></tr> }</tbody></table></div>
        </div>
        <footer>
          <a class="btn" [href]="pdfUrl()" [attr.download]="'rappel-' + lot().numero + '.pdf'">Exporter le dossier de rappel (PDF)</a>
          @if (done()) {
            <p class="ok-msg" role="status">{{ done() }}</p>
          } @else if (confirming()) {
            <div class="conf" role="alertdialog" aria-labelledby="conf-t">
              <p id="conf-t"><b>Bloquer {{ toBlock().length }} lot(s)</b> en quarantaine ? Le WMS sera notifié.</p>
              <button type="button" class="btn" (click)="confirming.set(false)">Annuler</button>
              <button type="button" class="btn pri" [disabled]="busy()" (click)="block()">Confirmer le blocage</button>
            </div>
          } @else {
            <button type="button" class="btn pri" [disabled]="!toBlock().length" (click)="confirming.set(true)">Bloquer le stock restant</button>
            @if (!toBlock().length) { <span class="note">Tout le stock restant est déjà bloqué.</span> }
          }
        </footer>
      } @else if (!error()) { <p class="note pad">Calcul du rappel…</p> }
    </section>
  `,
  styles: `
    .rap { border-top: 3px solid var(--crit); margin: 0 0 20px; padding: 14px 18px; }
    header, footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    footer { justify-content: flex-start; margin-top: 14px; .ok-msg { margin: 0; } }
    h3 { margin: 0; font-size: 17px; } h4 { margin: 14px 0 6px; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink2); }
    .kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-top: 12px;
      div { border: var(--bw) solid var(--line); padding: 8px 12px; } span { display: block; font-size: 12px; color: var(--ink2); } b { font-size: 22px; } }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
    .conf { display: flex; gap: 10px; align-items: center; border: var(--bw) solid var(--crit); padding: 6px 12px; p { margin: 0; } }
    @media (max-width: 860px) { .cols, .kpis { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RappelComponent {
  readonly lot = input.required<Lot>();
  readonly closed = output<void>();
  readonly blocked = output<void>();
  private readonly api = inject(LotsService);
  protected readonly ref = refLabel;
  protected readonly qty = formatUnitQty;
  protected readonly label = statusLabel;
  protected readonly tone = qualityTone;
  protected readonly recall = signal<Recall | null>(null);
  protected readonly confirming = signal(false);
  protected readonly busy = signal(false);
  protected readonly done = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly pdfUrl = computed(() => recallPdfUrl(this.lot().id));
  protected readonly toBlock = computed(() => {
    const r = this.recall();
    return r ? lotsToBlock(this.lot(), r) : [];
  });

  constructor() {
    effect(() => {
      const id = this.lot().id;
      untracked(() => void this.load(id));
    });
  }

  private async load(id: string): Promise<void> {
    try {
      this.recall.set(await this.api.recall(id));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de calculer le rappel.'));
    }
  }

  async block(): Promise<void> {
    this.busy.set(true);
    const reason = `Rappel fournisseur — lot ${this.lot().numero}`;
    try {
      const lots = await this.api.blockRecall(this.lot().id, reason);
      this.done.set(`${lots.length} lot(s) bloqué(s) en quarantaine.`);
      this.confirming.set(false);
      this.blocked.emit();
    } catch (e) {
      this.error.set(errorMessage(e, 'Le blocage a échoué : vérifiez les lots restants.'));
    } finally {
      this.busy.set(false);
    }
  }
}
