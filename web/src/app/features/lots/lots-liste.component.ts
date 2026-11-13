import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDate } from '../../core/labels';
import { Article, Lot, NewLot, Partner, QUALITY_STATUS_LABELS, qualityTone, refLabel, statusLabel } from '../../core/m3b.models';
import { LotsService, MasterDataService } from '../../core/m3b.service';

/** Ressources → Lots & traçabilité : recherche, filtre par statut qualité, réception d'un lot fournisseur. */
@Component({
  selector: 'eq-lots-liste',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ph"><div><span class="ref">Ressources</span><h2>Lots &amp; traçabilité</h2></div>
      <button type="button" class="btn pri" (click)="showForm.set(!showForm())">+ Réceptionner un lot</button></div>

    @if (showForm()) {
      <form class="box pad frm" (ngSubmit)="create()" aria-label="Réception d'un lot">
        <div class="row">
          <label class="field"><span>Article</span><select name="art" [(ngModel)]="draft.articleId" required>
            <option value="">—</option>@for (a of articles(); track a.id) { <option [value]="a.id">{{ a.code }} — {{ a.designation }}</option> }</select></label>
          <label class="field"><span>N° de lot</span><input name="num" [(ngModel)]="draft.numero" required /></label>
          <label class="field"><span>Fournisseur</span><select name="four" [(ngModel)]="draft.fournisseurId">
            <option [ngValue]="null">—</option>@for (p of suppliers(); track p.id) { <option [ngValue]="p.id">{{ p.code }} — {{ p.nom }}</option> }</select></label>
          <label class="field"><span>N° lot fournisseur</span><input name="lf" [(ngModel)]="draft.lotFournisseur" /></label>
          <label class="field"><span>Quantité</span><input name="qte" type="number" min="0" [(ngModel)]="draft.quantite" required /></label>
          <label class="field"><span>Unité</span><input name="u" [(ngModel)]="draft.unite" /></label>
          <label class="field"><span>DLC</span><input name="dlc" type="date" [(ngModel)]="draft.dlc" /></label>
        </div>
        <button type="submit" class="btn pri" [disabled]="busy() || !draft.articleId || !draft.numero">Enregistrer la réception</button>
      </form>
    }

    <div class="flt">
      <label class="field"><span>Recherche</span><input name="q" [(ngModel)]="q" (keyup.enter)="load()" placeholder="N° de lot, lot fournisseur, article" /></label>
      <label class="field"><span>Statut qualité</span><select name="st" [(ngModel)]="status" (ngModelChange)="load()">
        <option value="">Tous</option>@for (s of statuses; track s[0]) { <option [value]="s[0]">{{ s[1] }}</option> }</select></label>
      <button type="button" class="btn" (click)="load()">Rechercher</button>
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw"><table>
      <thead><tr><th>Lot</th><th>Article</th><th>Fournisseur</th><th>Lot fournisseur</th><th class="r">Quantité</th><th>DLC</th><th>Statut</th></tr></thead>
      <tbody>@for (l of lots(); track l.id) {
        <tr class="go" (click)="open(l)"><td><a class="lnk mono" [routerLink]="'/lots/' + l.id">{{ l.numero }}</a></td><td>{{ ref(l.article) }}</td>
          <td>{{ ref(l.fournisseur) }}</td><td class="mono">{{ l.lotFournisseur ?? '—' }}</td><td class="r mono">{{ l.quantite ?? '—' }} {{ l.unite }}</td>
          <td class="mono">{{ date(l.dlc) }}</td><td><span class="st" [class]="tone(l.qualityStatus)"><i></i>{{ label(l.qualityStatus) }}</span></td></tr>
      } @empty { <tr><td colspan="7" class="note">Aucun lot.</td></tr> }</tbody></table></div>
  `,
  styles: `
    .frm { margin-bottom: 18px; } .row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0 12px; }
    .flt { display: flex; gap: 12px; align-items: end; .field { margin-bottom: 14px; min-width: 220px; } .btn { margin-bottom: 14px; } }
    @media (max-width: 860px) { .row { grid-template-columns: 1fr; } .flt { flex-wrap: wrap; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LotsListeComponent {
  private readonly api = inject(LotsService);
  private readonly md = inject(MasterDataService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly statuses = Object.entries(QUALITY_STATUS_LABELS);
  protected readonly ref = refLabel;
  protected readonly label = statusLabel;
  protected readonly tone = qualityTone;
  protected readonly date = formatDate;
  protected q = '';
  protected status = '';
  protected draft: NewLot = this.blank();
  protected readonly lots = signal<Lot[]>([]);
  protected readonly articles = signal<Article[]>([]);
  protected readonly suppliers = signal<Partner[]>([]);
  protected readonly showForm = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.load();
    void this.loadMasterData();
  }

  private blank(): NewLot {
    return { articleId: '', numero: '', fournisseurId: null, lotFournisseur: null, quantite: 0, unite: 'pcs', dlc: null,
      siteId: this.auth.me()?.sites?.[0]?.id ?? null };
  }

  private async loadMasterData(): Promise<void> {
    try {
      const [a, p] = await Promise.all([this.md.articles(), this.md.partners()]);
      this.articles.set(a);
      this.suppliers.set(p.filter((x) => x.type === 'fournisseur'));
    } catch (e) {
      this.error.set(errorMessage(e, 'Données de base indisponibles : la réception est impossible.'));
    }
  }

  async load(): Promise<void> {
    try {
      this.lots.set(await this.api.list({ q: this.q.trim(), status: this.status }));
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les lots.'));
    }
  }

  async create(): Promise<void> {
    this.busy.set(true);
    try {
      const lot = await this.api.create({ ...this.draft, dlc: this.draft.dlc || null, lotFournisseur: this.draft.lotFournisseur || null,
        quantite: Number(this.draft.quantite) });
      await this.router.navigateByUrl(`/lots/${lot.id}`);
    } catch (e) {
      this.error.set(errorMessage(e, "La réception n'a pas pu être enregistrée."));
    } finally {
      this.busy.set(false);
    }
  }

  protected open(l: Lot): void {
    void this.router.navigateByUrl(`/lots/${l.id}`);
  }
}
