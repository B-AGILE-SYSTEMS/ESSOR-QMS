import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NotificationRule } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { NotificationsService } from '../../core/notifications.service';

export const EVENT_LABELS: Record<string, string> = {
  RECORD_ASSIGNED: 'Fiche assignée',
  TRANSITION_DONE: 'Transition effectuée',
  ACTION_OVERDUE: 'Action en retard',
  DUE_SOON: 'Échéance J-2',
};
export const CHANNEL_LABELS: Record<string, string> = { IN_APP: 'Dans l’application', EMAIL: 'E-mail' };

const uniq = (xs: string[]): string[] => [...new Set(xs)];

/** Paramétrage → Règles de notification : matrice événement × canal (ADMIN). */
@Component({
  selector: 'eq-regles-notification',
  standalone: true,
  template: `
    <div class="ph"><div><span class="ref">Paramétrage · Notifications</span><h2>Règles de notification</h2></div></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (info(); as msg) { <p class="ok-msg" role="status">{{ msg }}</p> }
    <div class="box tw">
      <table>
        <thead><tr><th>Événement</th>@for (c of channels(); track c) { <th>{{ channelLabel(c) }}</th> }</tr></thead>
        <tbody>
          @for (e of events(); track e) {
            <tr>
              <td><b>{{ eventLabel(e) }}</b> <span class="mono note">{{ e }}</span></td>
              @for (c of channels(); track c) {
                <td>
                  @if (find(e, c); as r) {
                    <input type="checkbox" [checked]="r.enabled" [attr.aria-label]="eventLabel(e) + ' — ' + channelLabel(c)" (change)="toggle(e, c)" />
                  } @else { <span class="note">—</span> }
                </td>
              }
            </tr>
          } @empty {
            <tr><td class="note">{{ loading() ? 'Chargement…' : 'Aucune règle.' }}</td></tr>
          }
        </tbody>
      </table>
    </div>
    <p class="note">L'e-mail n'est envoyé que si le serveur SMTP est configuré (ESSOR_SMTP_*).</p>
    <div class="bar"><button type="button" class="btn pri" [disabled]="saving() || !rules().length" (click)="save()">Enregistrer</button></div>
  `,
  styles: `
    input[type='checkbox'] { width: 20px; height: 20px; }
    .bar { display: flex; justify-content: flex-end; margin-top: 10px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglesNotificationComponent {
  private readonly notifications = inject(NotificationsService);
  protected readonly rules = signal<NotificationRule[]>([]);
  protected readonly events = computed(() => uniq(this.rules().map((r) => r.event)));
  protected readonly channels = computed(() => uniq(this.rules().map((r) => r.channel)));
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);

  constructor() {
    this.notifications
      .rules()
      .then((r) => this.rules.set(r), (e) => this.error.set(errorMessage(e)))
      .finally(() => this.loading.set(false));
  }

  protected find(event: string, channel: string): NotificationRule | undefined {
    return this.rules().find((r) => r.event === event && r.channel === channel);
  }

  toggle(event: string, channel: string): void {
    this.rules.update((rs) => rs.map((r) => (r.event === event && r.channel === channel ? { ...r, enabled: !r.enabled } : r)));
  }

  protected eventLabel(e: string): string {
    return EVENT_LABELS[e] ?? e;
  }

  protected channelLabel(c: string): string {
    return CHANNEL_LABELS[c] ?? c;
  }

  async save(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    this.info.set(null);
    try {
      this.rules.set(await this.notifications.saveRules(this.rules()));
      this.info.set('Règles enregistrées.');
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.saving.set(false);
    }
  }
}
