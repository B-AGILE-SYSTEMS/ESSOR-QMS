import { RecordData } from '../../core/api.models';

/** Analyse de causes stockée dans `data` de la fiche NC. */
export const ISHIKAWA_KEYS = ['methode', 'machine', 'matiere', 'mainOeuvre', 'milieu', 'mesure'] as const;
export type IshikawaKey = (typeof ISHIKAWA_KEYS)[number];
export const ISHIKAWA_LABELS: Record<IshikawaKey, string> = {
  methode: 'Méthode',
  machine: 'Machine',
  matiere: 'Matière',
  mainOeuvre: "Main-d'œuvre",
  milieu: 'Milieu',
  mesure: 'Mesure',
};
export const WHY_COUNT = 5;

export interface Analyse {
  analyse5p: string[];
  causeRacine: string;
  ishikawa: Record<IshikawaKey, string>;
}

const str = (v: unknown): string => (typeof v === 'string' ? v : '');

/** Lecture tolérante de `data` (champs absents ou mal typés → vides). */
export function readAnalyse(data: RecordData | null | undefined): Analyse {
  const whys = Array.isArray(data?.['analyse5p']) ? (data!['analyse5p'] as unknown[]).map(str) : [];
  const isk = (data?.['ishikawa'] ?? {}) as Record<string, unknown>;
  return {
    analyse5p: Array.from({ length: WHY_COUNT }, (_, i) => whys[i] ?? ''),
    causeRacine: str(data?.['causeRacine']),
    ishikawa: Object.fromEntries(ISHIKAWA_KEYS.map((k) => [k, str(isk[k])])) as Record<IshikawaKey, string>,
  };
}

/** Fusionne l'analyse dans `data` sans perdre les autres champs personnalisés. */
export function writeAnalyse(data: RecordData | null | undefined, a: Analyse): RecordData {
  const whys = a.analyse5p.map((w) => w.trim());
  while (whys.length && !whys[whys.length - 1]) whys.pop();
  return {
    ...(data ?? {}),
    analyse5p: whys,
    causeRacine: a.causeRacine.trim(),
    ishikawa: Object.fromEntries(ISHIKAWA_KEYS.map((k) => [k, a.ishikawa[k].trim()])),
  };
}
