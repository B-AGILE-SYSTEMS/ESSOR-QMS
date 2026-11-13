import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/m3b/gs1.dart';
import 'package:essor_mobile/core/m3b/models.dart';

const sscc = '106141411234567897';

Characteristic diam({
  double? nominal = 12,
  double? lo = 11.97,
  double? hi = 12.03,
}) => Characteristic(
  code: 'DIAM',
  libelle: 'Ø',
  type: CharType.mesure,
  nominal: nominal,
  tolMin: lo,
  tolMax: hi,
  criticite: 'critique',
);

void main() {
  group('GS1-128', () {
    test('AI 00, 17 et 10 avec FNC1 et préfixe de symbologie', () {
      final s = parseLotScan(']C100$sscc${'17261231'}10LOT-42$gs') as Gs1Label;
      expect(s.sscc, sscc);
      expect(s.expiry, DateTime(2026, 12, 31));
      expect(s.lot, 'LOT-42');
    });

    test('lot variable terminé par FNC1 puis DLC', () {
      final s = parseLotScan('10ABC123${gs}17270615') as Gs1Label;
      expect(s.lot, 'ABC123');
      expect(s.expiry, DateTime(2027, 6, 15));
    });

    test('notation entre parenthèses et GTIN', () {
      final s =
          parseLotScan('(01)03453120000011(17)260200(10)L2611-B') as Gs1Label;
      expect(s.gtin, '03453120000011');
      expect(s.lot, 'L2611-B');
      expect(s.expiry, DateTime(2026, 2, 28), reason: 'JJ=00 : fin de mois');
    });

    test('SSCC seul accepté, clé de contrôle vérifiée', () {
      expect((parseLotScan('00$sscc') as Gs1Label).sscc, sscc);
      expect(parseLotScan('00106141411234567890'), isNull);
    });

    test('rejets : date invalide, AI inconnu, tronqué, sans lot ni SSCC', () {
      expect(parseLotScan('10A${gs}17261331'), isNull);
      expect(parseLotScan('10A${gs}17260230'), isNull);
      expect(parseLotScan('5512345'), isNull);
      expect(parseLotScan('0010614141'), isNull);
      expect(parseLotScan('17261231'), isNull);
      expect(parseLotScan(''), isNull);
      expect(parseLotScan(null), isNull);
    });

    test('QR essor://lot/<id>', () {
      expect((parseLotScan('essor://lot/lot-1') as LotQr).id, 'lot-1');
      expect(parseLotScan('essor://zone/L2'), isNull);
      expect(parseLotScan('essor://lot/a/b'), isNull);
      expect(parseLotScan('essor://lot/%3Cx%3E'), isNull);
    });
  });

  group('verdict selon tolérances', () {
    test('limites absolues, bornes incluses', () {
      final c = diam();
      expect(measureVerdict(c, [12.0, 11.97, 12.03]), isTrue);
      expect(measureVerdict(c, [12.0, 12.05]), isFalse);
      expect(measureVerdict(c, [11.96]), isFalse);
      expect(measureVerdict(c, [12.0, null]), isNull);
      expect(measureVerdict(c, []), isNull);
    });

    test('écarts au nominal quand la limite haute est sous le nominal', () {
      final c = diam(lo: -0.03, hi: 0.03);
      expect(c.limits, (11.97, 12.03));
      expect(measureVerdict(c, [12.02]), isTrue);
      expect(measureVerdict(c, [12.04]), isFalse);
    });

    test('limite unique', () {
      final c = diam(nominal: null, lo: null, hi: 5);
      expect(measureVerdict(c, [4.9]), isTrue);
      expect(measureVerdict(c, [5.1]), isFalse);
    });

    test('décision proposée', () {
      const asp = Characteristic(
        code: 'ASP',
        libelle: 'Aspect',
        type: CharType.attribut,
        criticite: 'mineure',
      );
      final cs = [diam(), asp];
      CharResult r(String c, bool ok) =>
          CharResult(characteristic: c, values: [ok], ok: ok);
      expect(
        proposeDecision(cs, {'DIAM': r('DIAM', true), 'ASP': r('ASP', true)}),
        Decision.libere,
      );
      expect(
        proposeDecision(cs, {'DIAM': r('DIAM', true), 'ASP': r('ASP', false)}),
        Decision.quarantaine,
      );
      expect(
        proposeDecision(cs, {'DIAM': r('DIAM', false), 'ASP': r('ASP', true)}),
        Decision.refuse,
      );
    });
  });

  test('motif obligatoire hors LIBERE ; taille d\'échantillon du serveur', () {
    expect(decisionNeedsReason(Decision.libere), isFalse);
    for (final d in [
      Decision.quarantaine,
      Decision.derogation,
      Decision.refuse,
    ]) {
      expect(decisionNeedsReason(d), isTrue);
    }
    final p = ControlPlan.fromJson({
      'id': 'c',
      'sampling': {'mode': 'iso2859', 'nqa': 1.0, 'sampleSize': 13, 'ac': 0},
      'target': {'type': 'famille', 'famille': 'F1'},
      'characteristics': [],
    });
    expect(p.sampleSize, 13);
    expect(p.targetKeys, ['F1']);
  });

  test('plan applicable au lot par article, famille ou fournisseur', () {
    final lot = Lot.fromJson({
      'id': 'l',
      'numero': 'N1',
      'article': {'id': 'a1', 'code': 'AX', 'famille': 'F1'},
      'supplier': {'id': 'p1'},
      'supplierLot': 'S9',
    });
    ControlPlan plan(Map<String, Object?> t) =>
        ControlPlan.fromJson({'id': 'c', 'target': t, 'characteristics': []});
    expect(plan({'type': 'article', 'id': 'a1'}).appliesTo(lot), isTrue);
    expect(plan({'famille': 'F1'}).appliesTo(lot), isTrue);
    expect(
      plan({
        'supplier': {'id': 'p1'},
      }).appliesTo(lot),
      isTrue,
    );
    expect(plan({'articleId': 'zz'}).appliesTo(lot), isFalse);
    expect(plan({}).appliesTo(lot), isTrue);
    expect(lot.matchesNumber('s9'), isTrue);
  });
}
