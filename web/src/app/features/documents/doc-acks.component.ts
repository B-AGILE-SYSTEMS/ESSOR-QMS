import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { QmsRecord } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';
import { formatDateTime } from '../../core/labels';
import { AckStatus, DocVersion } from '../../core/m3.models';
import { DocumentsService } from '../../core/m3.service';

/** Onglet « Diffusion & lecture » : destinataires, accusés de lecture de la version en vigueur (qui a lu, qui reste). */
@Component({
  selector: 'eq-doc-acks',
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="two">
      <div class="box pad">
        <h3 class="t">Accusés de lecture @if (version(); as v) { — version {{ v.number }} }</h3>
        @if (version(); as v) {
          @if (acks(); as a) {
            <p class="bar"><b class="num">{{ a.read.length }}/{{ a.read.length + a.pending.length }}</b> <span class="note">destinataires ont lu</span>
              <span class="gauge"><i [style.width.%]="rate()"></i></span></p>
            <div class="cols">
              <div><h4>Ont lu</h4><ul>@for (r of a.read; track r.userId) { <li>✓ {{ r.displayName }} <span class="note mono">{{ dt(r.at) }}</span></li> }
                @empty { <li class="note">Personne.</li> }</ul></div>
              <div><h4>Restent à lire</h4><ul>@for (r of a.pending; track r.userId) { <li>{{ r.displayName }}</li> }
                @empty { <li class="note">Tout le monde a lu.</li> }</ul></div>
            </div>
            @if (mustAck()) {
              <button type="button" class="btn pri" [disabled]="busy()" (click)="ack(v)">J'ai lu et compris la version {{ v.number }}</button>
            }
          }
        } @else { <p class="note">Aucune version diffusée : les accusés de lecture démarrent à la diffusion.</p> }
      </div>
      <div class="box pad">
        <h3 class="t">Destinataires</h3>
        <dl class="kv">
          <dt>Rôles</dt><dd>{{ roles().join(', ') || '—' }}</dd>
          <dt>Sites</dt><dd>{{ sites().join(', ') || 'Tous' }}</dd>
          <dt>Vérificateur</dt><dd>{{ list('verifierRoles') }}</dd>
          <dt>Approbateur</dt><dd>{{ list('approverRoles') }}</dd>
        </dl>
      </div>
    </div>
  `,
  styles: `
    .t { margin: 0 0 10px; font-size: 16px; }
    .bar { display: flex; gap: 10px; align-items: center; }
    .gauge { flex: 1; height: 8px; background: var(--surf2); border: var(--bw) solid var(--line); i { display: block; height: 100%; background: var(--ok); } }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; h4 { margin: 8px 0 4px; font-size: 13px; text-transform: uppercase; color: var(--ink2); } ul { margin: 0 0 12px; padding-left: 0; list-style: none; } li { padding: 3px 0; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocAcksComponent {
  private readonly docs = inject(DocumentsService);
  private readonly auth = inject(AuthService);
  readonly record = input.required<QmsRecord>();

  protected readonly version = signal<DocVersion | null>(null);
  protected readonly acks = signal<AckStatus | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly dt = formatDateTime;

  private readonly dest = computed(() => (this.record().data['recipients'] ?? this.record().data['destinataires'] ?? {}) as { roles?: string[]; siteIds?: string[] });
  protected readonly roles = computed(() => this.dest().roles ?? []);
  protected readonly sites = computed(() => {
    const all = this.auth.me()?.sites ?? [];
    return (this.dest().siteIds ?? []).map((id) => all.find((s) => s.id === id)?.name ?? id);
  });
  protected readonly rate = computed(() => {
    const a = this.acks();
    const n = a ? a.read.length + a.pending.length : 0;
    return n ? Math.round((a!.read.length / n) * 100) : 0;
  });
  /** L'utilisateur courant figure parmi ceux qui restent à lire. */
  protected readonly mustAck = computed(() => {
    const me = this.auth.me();
    return !!me && !!this.acks()?.pending.some((p) => p.userId === me.username || p.displayName === me.displayName);
  });

  protected list(key: string): string {
    const v = this.record().data[key];
    return Array.isArray(v) && v.length ? v.join(', ') : '—';
  }

  constructor() {
    effect(() => {
      const id = this.record().id;
      untracked(() => void this.load(id));
    });
  }

  private async load(id: string): Promise<void> {
    try {
      const v = (await this.docs.versions(id)).find((x) => x.current || x.status === 'DIFFUSE') ?? null;
      this.version.set(v);
      this.acks.set(v ? await this.docs.acks(id, v.number) : null);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les accusés de lecture.'));
    }
  }

  async ack(v: DocVersion): Promise<void> {
    this.busy.set(true);
    try {
      await this.docs.ack(this.record().id, v.number);
      this.acks.set(await this.docs.acks(this.record().id, v.number));
    } catch (e) {
      this.error.set(errorMessage(e, "L'accusé de lecture n'a pas été enregistré."));
    } finally {
      this.busy.set(false);
    }
  }
}
