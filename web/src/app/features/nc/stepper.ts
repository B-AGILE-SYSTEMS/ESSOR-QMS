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
  // État final du chemin principal : celui atteint depuis le dernier état non final.
  const finals = new Set(wf.states.filter((s) => s.final).map((s) => s.code));
  const lastOpen = [...wf.states].reverse().find((s) => !s.final)?.code;
  const last =
    wf.transitions.find((t) => t.from === lastOpen && finals.has(t.to))?.to ?? [...wf.states].reverse().find((s) => s.final)?.code;
  const shown = wf.states.filter((s) => !s.final || s.code === current || s.code === last);
  const idx = shown.findIndex((s) => s.code === current);
  return shown.map((s, i) => ({
    code: s.code,
    label: s.label,
    state: i === idx ? 'cur' : idx >= 0 && i < idx ? 'done' : 'todo',
  }));
}
