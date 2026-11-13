import { Component, ChangeDetectionStrategy } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { RouterOutlet } from '@angular/router';
import { appConfig } from './app/app.config';

@Component({
  selector: 'eq-root',
  imports: [RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<router-outlet />',
})
class RootComponent {}

bootstrapApplication(RootComponent, appConfig).catch(() => {
  document.body.textContent =
    "ESSOR QMS n'a pas pu démarrer. Rechargez la page.";
});
