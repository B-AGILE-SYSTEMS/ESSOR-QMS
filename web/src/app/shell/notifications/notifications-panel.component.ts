import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppNotification } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { formatDateTime } from '../../core/labels';
import { NotificationsService } from '../../core/notifications.service';

/** Lien interne vers la fiche (jamais d'URL externe). */
export function notificationTarget(n: AppNotification): string | null {
  if (n.link && n.link.startsWith('/') && !n.link.startsWith('//')) return n.link;
  return n.recordId ? `/non-conformites/${encodeURIComponent(n.recordId)}` : null;
}

/** Panneau des notifications : liste, marquer lu, tout marquer lu. */
@Component({
  selector: 'eq-notifications-panel',
  standalone: true,
  template: `
    <div id="notif-panel" class="panel" role="region" aria-label="Notifications">
      <div class="hd">
        <b>Notifications</b>
        <button type="button" class="btn ghost sm" [disabled]="!svc.unread()" (click)="readAll()">Tout marquer lu</button>
      </div>
      @if (error(); as msg) { <p class="err">{{ msg }}</p> }
      <ul>
        @for (n of svc.items(); track n.id) {
          <li [class.unread]="!n.read">
            <button type="button" class="open" (click)="open(n)">
              <span class="t">{{ n.title }}</span>
              <span class="mono note">{{ when(n.createdAt) }}</span>
            </button>
            @if (!n.read) {
              <button type="button" class="btn ghost sm" [attr.aria-label]="'Marquer lu : ' + n.title" (click)="read(n)">Lu</button>
            }
          </li>
        } @empty {
          <li class="note empty">Aucune notification.</li>
        }
      </ul>
    </div>
  `,
  styles: `
    .panel { position: absolute; right: 0; top: calc(100% + 6px); width: 380px; z-index: 40; background: var(--surf);
      border: var(--bw) solid var(--line2); box-shadow: 0 8px 24px rgba(0,0,0,.18); }
    .hd { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; border-bottom: 2px solid var(--ink); }
    ul { list-style: none; margin: 0; padding: 0; max-height: 420px; overflow: auto; }
    li { display: flex; align-items: center; gap: 6px; border-bottom: var(--bw) solid var(--line); padding-right: 6px; }
    li.unread { border-left: 4px solid var(--acc); }
    li.unread .t { font-weight: 700; }
    .open { flex: 1; text-align: left; background: none; border: 0; padding: 10px 12px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .open:hover { background: var(--surf2); }
    .sm { min-height: 30px; padding: 4px 8px; font-size: 13px; }
    .empty { padding: 12px; }
    .err { margin: 8px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPanelComponent {
  protected readonly svc = inject(NotificationsService);
  private readonly router = inject(Router);
  readonly closed = output<void>();
  protected readonly error = signal<string | null>(null);
  protected readonly when = formatDateTime;

  async read(n: AppNotification): Promise<void> {
    try {
      await this.svc.markRead(n.id);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async readAll(): Promise<void> {
    try {
      await this.svc.markAllRead();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async open(n: AppNotification): Promise<void> {
    if (!n.read) await this.read(n);
    const target = notificationTarget(n);
    if (target) {
      this.closed.emit();
      await this.router.navigateByUrl(target);
    }
  }
}
