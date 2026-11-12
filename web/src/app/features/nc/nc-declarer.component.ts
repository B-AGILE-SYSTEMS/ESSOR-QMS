import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { SEVERITIES, Severity } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { SEVERITY_LABELS } from '../../core/labels';
import { RecordsService } from '../../core/records.service';

export const NC_CATEGORIES = ['Produit', 'Processus', 'Sécurité', 'Environnement', 'Fournisseur'] as const;

/** Déclaration d'une NC : `POST /api/records` puis ouverture de la fiche. */
@Component({
    selector: 'eq-nc-declarer',
    imports: [ReactiveFormsModule],
    template: `
    <div class="ph"><div><span class="ref">Nouvelle non-conformité</span><h2>Déclarer une non-conformité</h2></div></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="two">
        <div class="box pad">
          <label class="field"><span>Que s'est-il passé ? *</span>
            <input formControlName="title" maxlength="200" required />
          </label>
          <label class="field"><span>Description</span>
            <textarea formControlName="description" rows="4"></textarea>
          </label>
          <div class="field" role="group" aria-labelledby="lbl-cat"><span id="lbl-cat">Type</span>
            <div class="chips">
              @for (c of categories; track c) {
                <button type="button" class="chip" [attr.aria-pressed]="form.value.category === c" (click)="form.patchValue({ category: c })">{{ c }}</button>
              }
            </div>
          </div>
          <div class="field" role="group" aria-labelledby="lbl-sev"><span id="lbl-sev">Gravité</span>
            <div class="chips">
              @for (s of severities; track s) {
                <button type="button" class="chip" [attr.aria-pressed]="form.value.severity === s" (click)="form.patchValue({ severity: s })">{{ sevLabel[s] }}</button>
              }
            </div>
          </div>
          <label class="field"><span>Site *</span>
            <select formControlName="siteId" required>
              <option value="">Choisir un site…</option>
              @for (s of auth.me()?.sites ?? []; track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
          </label>
          <label class="field"><span>Zone / lieu</span><input formControlName="zone" /></label>
          <label class="field"><span>Constaté le</span><input type="datetime-local" class="mono" formControlName="observedAt" /></label>
        </div>
        <div class="box pad">
          <p class="note">Seuls le titre et le site sont obligatoires. La qualification (gravité définitive, responsable) est faite à l'étape suivante.</p>
        </div>
      </div>
      <div class="nextbar">
        <p>Étape suivante : <b>Qualifier</b> — gravité, responsable et échéance.</p>
        <button type="submit" class="btn pri" [disabled]="saving()">{{ saving() ? 'Enregistrement…' : 'Enregistrer la déclaration →' }}</button>
      </div>
    </form>
  `,
    styles: `.field .chips { margin-top: 2px; }`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NcDeclarerComponent {
  protected readonly auth = inject(AuthService);
  private readonly records = inject(RecordsService);
  private readonly router = inject(Router);

  protected readonly categories = NC_CATEGORIES;
  protected readonly severities = SEVERITIES;
  protected readonly sevLabel = SEVERITY_LABELS;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
    category: ['Produit' as string],
    severity: ['mineure' as Severity],
    siteId: [this.auth.me()?.sites.length === 1 ? this.auth.me()!.sites[0].id : '', Validators.required],
    zone: [''],
    observedAt: [''],
  });

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Renseignez le titre et le site.');
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    try {
      const rec = await this.records.create({
        type: 'NC',
        siteId: v.siteId,
        title: v.title.trim(),
        description: v.description.trim(),
        severity: v.severity,
        data: { category: v.category, zone: v.zone.trim() || null, observedAt: v.observedAt || null },
      });
      await this.router.navigate(['/non-conformites', rec.id]);
    } catch (e) {
      this.error.set(errorMessage(e, "La déclaration n'a pas pu être enregistrée."));
    } finally {
      this.saving.set(false);
    }
  }
}
