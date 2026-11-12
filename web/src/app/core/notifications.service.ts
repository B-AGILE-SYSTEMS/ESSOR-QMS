import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppNotification, NotificationRule } from './api.models';

export const NOTIFICATION_STREAM_URL = '/api/notifications/stream';

/** Fabrique d'EventSource (remplaçable en test). Session web : le cookie est envoyé. */
export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSource>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url, { withCredentials: true }),
});

/** Notifications in-app : liste, compteur non lues, marquage, temps réel (SSE). */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);
  private readonly openStream = inject(EVENT_SOURCE_FACTORY);
  private source: EventSource | null = null;

  private readonly _items = signal<AppNotification[]>([]);
  readonly items = this._items.asReadonly();
  readonly unread = computed(() => this._items().filter((n) => !n.read).length);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.disconnect());
  }

  async load(): Promise<void> {
    const page = await firstValueFrom(
      this.http.get<{ items: AppNotification[]; unread: number }>('/api/notifications'),
    );
    this._items.set(sortByDate(page?.items ?? []));
  }

  /** Ouvre le flux SSE ; chaque message (anonyme ou `event: notification`) porte une notification JSON. */
  connect(): void {
    if (this.source) {
      return;
    }
    const src = this.openStream(NOTIFICATION_STREAM_URL);
    const onEvent = (e: MessageEvent<string>) => this.receive(e.data);
    src.onmessage = onEvent;
    src.addEventListener('notification', onEvent as EventListener);
    this.source = src;
  }

  disconnect(): void {
    this.source?.close();
    this.source = null;
  }

  receive(raw: string): void {
    let n: AppNotification;
    try {
      n = JSON.parse(raw) as AppNotification;
    } catch {
      return;
    }
    if (!n || typeof n.id !== 'string') {
      return;
    }
    const item: AppNotification = { ...n, read: !!n.read, createdAt: n.createdAt ?? new Date().toISOString() };
    this._items.update((list) => sortByDate([item, ...list.filter((x) => x.id !== item.id)]));
  }

  async markRead(id: string): Promise<void> {
    await firstValueFrom(this.http.post(`/api/notifications/${encodeURIComponent(id)}/read`, null));
    this._items.update((list) => list.map((n) => (n.id === id ? { ...n, read: true } : n)));
  }

  async markAllRead(): Promise<void> {
    await firstValueFrom(this.http.post('/api/notifications/read-all', null));
    this._items.update((list) => list.map((n) => ({ ...n, read: true })));
  }

  rules(): Promise<NotificationRule[]> {
    return firstValueFrom(this.http.get<NotificationRule[]>('/api/notification-rules'));
  }

  saveRules(rules: NotificationRule[]): Promise<NotificationRule[]> {
    return firstValueFrom(this.http.put<NotificationRule[]>('/api/notification-rules', rules));
  }
}

function sortByDate(list: AppNotification[]): AppNotification[] {
  return [...list].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
}
