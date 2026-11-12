import { ChangeDetectionStrategy, Component, HostListener, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { BrandingService } from '../core/branding.service';
import { NAV_FAMILIES, NavItem } from './navigation.model';
import { NotificationsService } from '../core/notifications.service';
import { NotificationsPanelComponent } from './notifications/notifications-panel.component';
import { PaletteComponent } from './palette.component';

interface Crumb {
  label: string;
}

@Component({
    selector: 'eq-shell',
    imports: [RouterOutlet, RouterLink, RouterLinkActive, PaletteComponent, NotificationsPanelComponent],
    templateUrl: './shell.component.html',
    styleUrl: './shell.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly branding = inject(BrandingService);
  protected readonly notifications = inject(NotificationsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly families = computed(() =>
    NAV_FAMILIES.map((f) => ({ ...f, items: f.items.filter((i) => this.visible(i)) })).filter((f) => f.items.length),
  );
  protected readonly site = signal('');
  protected readonly paletteOpen = signal(false);
  protected readonly notifOpen = signal(false);

  protected readonly crumbs = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.buildCrumbs()),
    ),
    { initialValue: this.buildCrumbs() },
  );

  constructor() {
    this.notifications.load().catch(() => undefined);
    this.notifications.connect();
  }

  private visible(i: NavItem): boolean {
    return !i.role || this.auth.hasRole(i.role);
  }

  private buildCrumbs(): Crumb[] {
    const out: Crumb[] = [];
    let r: ActivatedRoute | null = this.route.root;
    while (r) {
      const data = r.snapshot?.data as { crumbs?: string[] } | undefined;
      if (data?.crumbs && r.snapshot.routeConfig) {
        out.splice(0, out.length, ...data.crumbs.map((label) => ({ label })));
      }
      r = r.firstChild;
    }
    return out;
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.paletteOpen.set(true);
    }
  }

  protected onSite(e: Event): void {
    this.site.set((e.target as HTMLSelectElement).value);
  }

  protected logout(): void {
    void this.auth.logout();
  }
}
