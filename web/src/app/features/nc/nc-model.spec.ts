import { Workflow } from '../../core/api.models';
import { fieldLabel } from '../../core/labels';
import { notificationTarget } from '../../shell/notifications/notifications-panel.component';
import { parseWorkflow } from '../parametrage/workflows.component';
import { matches } from '../mes-actions/mes-actions.component';
import { readAnalyse, writeAnalyse } from './analyse.model';
import { defaultTab } from './nc-fiche.component';
import { buildSteps } from './stepper';

const WF: Workflow = {
  initial: 'DECLAREE',
  states: [
    { code: 'DECLAREE', label: 'Déclarée', final: false },
    { code: 'QUALIFIEE', label: 'Qualifiée', final: false },
    { code: 'ANALYSE', label: 'Analyse', final: false },
    { code: 'REJETEE', label: 'Rejetée', final: true },
    { code: 'CLOTUREE', label: 'Clôturée', final: true },
  ],
  transitions: [],
};

describe('Modèle NC', () => {
  it("construit la barre d'étapes (états finaux de côté masqués)", () => {
    const steps = buildSteps(WF, 'ANALYSE');
    expect(steps.map((s) => s.code)).toEqual(['DECLAREE', 'QUALIFIEE', 'ANALYSE', 'CLOTUREE']);
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'cur', 'todo']);
    expect(buildSteps(WF, 'REJETEE').some((s) => s.code === 'REJETEE' && s.state === 'cur')).toBeTrue();
    expect(buildSteps(null, 'X')).toEqual([]);
  });

  it("lit et écrit l'analyse dans data sans perdre les autres champs", () => {
    const a = readAnalyse({ analyse5p: ['p1', 2], zone: 'L2' });
    expect(a.analyse5p).toEqual(['p1', '', '', '', '']);
    a.causeRacine = ' Procédure incomplète ';
    a.ishikawa.methode = 'PR-USI-07';
    const data = writeAnalyse({ zone: 'L2' }, a);
    expect(data['zone']).toBe('L2');
    expect(data['analyse5p']).toEqual(['p1']);
    expect(data['causeRacine']).toBe('Procédure incomplète');
    expect((data['ishikawa'] as Record<string, string>)['methode']).toBe('PR-USI-07');
  });

  it("ouvre l'onglet adapté à l'étape", () => {
    expect(defaultTab('ANALYSE')).toBe('analyse');
    expect(defaultTab('VERIFICATION')).toBe('actions');
    expect(defaultTab('DECLAREE')).toBe('description');
  });

  it('valide la forme du JSON de workflow côté client', () => {
    expect(parseWorkflow('{').error).toContain('JSON invalide');
    expect(parseWorkflow('{"initial":"A"}').error).toContain('Structure');
    expect(parseWorkflow(JSON.stringify(WF)).wf?.initial).toBe('DECLAREE');
  });

  it('libellés de champs manquants et liens de notification internes', () => {
    expect(fieldLabel('data.causeRacine')).toBe('Cause racine');
    expect(fieldLabel('inconnu')).toBe('inconnu');
    const base = { id: '1', type: 'T', title: 't', read: false, createdAt: '' };
    expect(notificationTarget({ ...base, recordId: 'r1' })).toBe('/non-conformites/r1');
    expect(notificationTarget({ ...base, link: 'https://evil.example' })).toBeNull();
    expect(notificationTarget({ ...base, link: '/non-conformites/r2' })).toBe('/non-conformites/r2');
  });

  it('filtre Mes actions', () => {
    const t = { id: '1', reference: 'R', type: 'NC', status: 'VERIFICATION', statusLabel: '', title: '', overdue: false };
    expect(matches(t, 'a-faire')).toBeTrue();
    expect(matches(t, 'a-valider')).toBeTrue();
    expect(matches(t, 'en-retard')).toBeFalse();
  });
});
