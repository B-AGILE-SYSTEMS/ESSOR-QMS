/**
 * Garde-fou M4 : exemples RÉELS de l'API métrologie publiés par le back (origin/m4/back, docs/api-exemples/*.json, commit a792be7),
 * recopiés dans `exemples-m4/` tant que la branche back n'est pas intégrée. À l'intégration, pointer vers docs/api-exemples.
 */
import effet from './exemples-m4/effet-instrument.json';
import etalonnage from './exemples-m4/etalonnage.json';
import instrument from './exemples-m4/instrument.json';
import quarantaine from './exemples-m4/quarantaine-instrument.json';
import { isUsable, lotsToQuarantine, toCalibration, toImpact, toInstrument, toQuarantine } from './m4.models';

type Raw = Record<string, unknown>;
const raw = (x: unknown) => x as Raw;

describe('Lecture des exemples réels M4 (métrologie)', () => {
  it('fiche instrument {instrument, calibrations}', () => {
    const i = toInstrument(raw(instrument));
    expect(i.id).toBe(instrument.instrument.id);
    expect(i.code).toBe(instrument.instrument.code);
    expect(i.designation).toBe(instrument.instrument.label);
    expect(i.emt).toBe(instrument.instrument.emt);
    expect(i.periodicityMonths).toBe(instrument.instrument.periodMonths);
    expect(i.nextDue).toBe(instrument.instrument.nextDue);
    expect(i.range).toBe('0–150 mm');
    expect(i.qr).toBe(instrument.instrument.qr);
    expect(i.selectable).toBeTrue();
    expect(isUsable(i)).toBeTrue();
    expect(i.calibrations.length).toBe(instrument.calibrations.length);
    expect(i.calibrations[0].date).toBe(instrument.calibrations[0].calibratedOn);
  });

  it('étalonnage : points avec écart, verdict, certificat, échéance', () => {
    const c = toCalibration(raw(etalonnage));
    expect(c.date).toBe(etalonnage.calibratedOn);
    expect(c.points.map((p) => p.ecart)).toEqual(etalonnage.points.map((p) => p.ecart));
    expect(c.verdict).toBe(etalonnage.verdict);
    expect(c.nextDue).toBe(etalonnage.nextDue);
    expect(c.certificateNumber).toBe(etalonnage.certificateNumber);
    expect(c.certificateUrl).toBe(etalonnage.certificate.url);
    expect(c.by).toBe(etalonnage.createdBy);
  });

  it('effet instrument : depuis, contrôles, lots ; instrument bloqué non sélectionnable', () => {
    const im = toImpact(raw(effet));
    expect(im.since).toBe(effet.since);
    expect(im.inspections[0]).toEqual({ id: effet.inspections[0].id, lotId: effet.inspections[0].lotId, lotNumero: effet.inspections[0].lotNumero,
      point: 'RECEPTION', at: effet.inspections[0].measuredAt, characteristic: 'DIAM' });
    expect(im.lots[0].numero).toBe(effet.lots[0].numero);
    expect(im.lots[0].article).toBe(`${effet.lots[0].article.code} — ${effet.lots[0].article.label}`);
    expect(lotsToQuarantine(im).length).toBe(1);
    expect(isUsable(toInstrument(raw(effet['instrument'])))).toBeFalse();
    expect(toImpact({ lastConformingCalibration: { calibratedOn: '2026-01-02' } }).since).toBe('2026-01-02');
  });

  it('quarantaine : lots passés en quarantaine et NC liée', () => {
    const q = toQuarantine(raw(quarantaine));
    expect(q.quarantined).toEqual([{ id: quarantaine.quarantined[0].id, numero: quarantaine.quarantined[0].numero, previousStatus: 'EN_ATTENTE' }]);
    expect(q.ncId).toBe(quarantaine.ncId);
  });
});
