import { InjectionToken } from '@angular/core';

/** Abstraction de la navigation pleine page (testable). */
export interface PageNavigator {
  assign(url: string): void;
}

export const PAGE_NAVIGATOR = new InjectionToken<PageNavigator>('PAGE_NAVIGATOR', {
  providedIn: 'root',
  factory: () => ({ assign: (url: string) => window.location.assign(url) }),
});
