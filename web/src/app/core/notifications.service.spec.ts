import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EVENT_SOURCE_FACTORY, NOTIFICATION_STREAM_URL, NotificationsService } from './notifications.service';

class FakeEventSource {
  onmessage: ((e: MessageEvent<string>) => void) | null = null;
  closed = false;
  readonly listeners: Record<string, (e: MessageEvent<string>) => void> = {};
  constructor(readonly url: string) {}
  addEventListener(type: string, fn: (e: MessageEvent<string>) => void): void {
    this.listeners[type] = fn;
  }
  close(): void {
    this.closed = true;
  }
}

describe('NotificationsService', () => {
  let svc: NotificationsService;
  let http: HttpTestingController;
  let es: FakeEventSource | null;

  beforeEach(() => {
    es = null;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => (es = new FakeEventSource(url)) },
      ],
    });
    svc = TestBed.inject(NotificationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const n = (id: string, read = false, createdAt = '2026-11-12T08:00:00Z') => ({ id, type: 'X', title: id, read, createdAt });

  it('charge la liste et compte les non lues', async () => {
    const p = svc.load();
    http.expectOne('/api/notifications').flush([n('a'), n('b', true)]);
    await p;
    expect(svc.items().length).toBe(2);
    expect(svc.unread()).toBe(1);
  });

  it('reçoit en temps réel par SSE (message anonyme ou nommé), sans doublon', () => {
    svc.connect();
    expect(es!.url).toBe(NOTIFICATION_STREAM_URL);
    es!.onmessage!(new MessageEvent('message', { data: JSON.stringify(n('a', false, '2026-11-12T09:00:00Z')) }));
    es!.listeners['notification'](new MessageEvent('notification', { data: JSON.stringify(n('b', false, '2026-11-12T10:00:00Z')) }));
    es!.onmessage!(new MessageEvent('message', { data: JSON.stringify(n('a', false, '2026-11-12T09:00:00Z')) }));
    es!.onmessage!(new MessageEvent('message', { data: 'pas du json' }));
    expect(svc.items().map((x) => x.id)).toEqual(['b', 'a']);
    expect(svc.unread()).toBe(2);
    svc.disconnect();
    expect(es!.closed).toBeTrue();
  });

  it('marque une notification lue puis toutes', async () => {
    svc.receive(JSON.stringify(n('a')));
    svc.receive(JSON.stringify(n('b')));
    const p1 = svc.markRead('a');
    http.expectOne({ method: 'POST', url: '/api/notifications/a/read' }).flush(null);
    await p1;
    expect(svc.unread()).toBe(1);
    const p2 = svc.markAllRead();
    http.expectOne({ method: 'POST', url: '/api/notifications/read-all' }).flush(null);
    await p2;
    expect(svc.unread()).toBe(0);
  });
});
