import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MesActionsComponent } from './mes-actions.component';

describe('MesActionsComponent', () => {
  it('affiche les indicateurs et les tâches de /api/my-tasks, filtrables', async () => {
    TestBed.configureTestingModule({
      imports: [MesActionsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(MesActionsComponent);
    fixture.detectChanges();
    http.expectOne('/api/my-tasks').flush([
      { id: '1', reference: 'NC-2026-0001', type: 'NC', status: 'DECLAREE', statusLabel: 'Déclarée', title: 'Qualifier', dueDate: '2026-11-12', overdue: false },
      { id: '2', reference: 'ACT-2026-0001', type: 'ACTION', status: 'EN_COURS', statusLabel: 'En cours', title: 'Garde-corps', dueDate: '2026-11-10', overdue: true },
    ]);
    http.expectOne('/api/dashboard/summary').flush({ ncOpen: 14, ncToQualify: 5, actionsOverdue: 1, ncCostMonth: 48300 });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.kpi.dom .v')!.textContent).toBe('1');
    expect(el.textContent).toContain('14');
    expect(el.textContent).not.toContain("Données d'exemple");
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    const enRetard = Array.from(el.querySelectorAll<HTMLButtonElement>('.chip')).find((b) => b.textContent!.includes('En retard'))!;
    expect(enRetard.textContent).toContain('(1)');
    enRetard.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    expect(el.querySelector('tbody')!.textContent).toContain('en retard');
    http.verify();
  });
});
