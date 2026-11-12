import { Workflow } from '../../core/api.models';

export interface Step {
  code: string;
  label: string;
  state: 'done' | 'cur' | 'todo';
}

/**
 * Barre d'étapes dérivée du workflow : états dans l'ordre déclaré ; les états finaux « de côté »
 * (ex. REJETEE) ne sont montrés que si la fiche s'y trouve.
 */
export function buildSteps(wf: Workflow | null, current: string): Step[] {
  if (!wf) return [];
  const last = wf.states[wf.states.length - 1]?.code;
  const shown = wf.states.filter((s) => !s.final || s.code === current || s.code === last);
  const idx = shown.findIndex((s) => s.code === current);
  return shown.map((s, i) => ({
    code: s.code,
    label: s.label,
    state: i === idx ? 'cur' : idx >= 0 && i < idx ? 'done' : 'todo',
  }));
}
