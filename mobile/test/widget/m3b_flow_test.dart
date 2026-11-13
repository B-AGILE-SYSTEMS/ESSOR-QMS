import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'm2_flow_test.dart'
    show
        pumpM2,
        settle,
        settleSynced,
        settleUntil,
        tap,
        tapPhoto,
        shot,
        loadFonts,
        shotDir,
        expectTargets;

void main() {
  setUpAll(() async {
    if (shotDir != null) await loadFonts();
  });

  testWidgets('contrôle de lot hors ligne guidé par le plan puis synchro', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    // Le premier cycle de synchro a mis lots et plans en cache.
    await settleUntil(
      tester,
      () async => await h.db.getCache('m3b.plans') != null,
    );
    h.server.online = false;
    h.online.add(false);
    await settle(tester);

    await tap(tester, 'controlButton');
    await tap(tester, 'scanLot');
    await settleUntil(
      tester,
      () => find.text('Lot L2611-B').evaluate().isNotEmpty,
    );
    // Lot retrouvé hors ligne par son numéro fournisseur (AI 10).
    expect(find.text('Lot L2611-B'), findsOneWidget);
    expect(find.byKey(const Key('plan-cp-1')), findsOneWidget);
    expect(find.byKey(const Key('plan-cp-9')), findsNothing);
    await tap(tester, 'plan-cp-1');

    expect(find.text('Caractéristique 1 sur 2'), findsOneWidget);
    expect(find.text('Tolérance 11,97 – 12,03 mm'), findsOneWidget);
    expect(find.text('Instrument requis : micromètre'), findsOneWidget);
    expect(find.byKey(const Key('value-1')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('value-0')), '12,01');
    await tester.enterText(find.byKey(const Key('value-1')), '12,05');
    await settle(tester);
    expect(find.text('Verdict : Hors tolérance ✕'), findsOneWidget);
    expect(find.text('Hors tolérance'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('instrument')), 'PAC-0142');
    await settle(tester);
    await shot(tester, 'controle-mesure-hors-ligne');
    await tap(tester, 'controlNext');

    expect(find.text('Caractéristique 2 sur 2'), findsOneWidget);
    expectTargets(tester, find.byKey(const Key('attr-ok')));
    await tap(tester, 'attr-ok');
    await tap(tester, 'controlNext');

    expect(find.text('Décision proposée : Refuser'), findsOneWidget);
    // FIELD_USER : saisie des résultats, décision par le responsable qualité.
    expect(find.text('Décision par le responsable qualité.'), findsOneWidget);
    expect(find.byKey(const Key('dec-REFUSE')), findsNothing);
    expect(find.byKey(const Key('decisionComment')), findsNothing);
    await tapPhoto(tester, 'controlPhoto', 1);
    expect(find.text('Photo (1)'), findsOneWidget);
    await shot(tester, 'controle-decision-hors-ligne');
    await tap(tester, 'controlSave');
    await settleUntil(
      tester,
      () => find.text('Hors ligne · 3 en attente').evaluate().isNotEmpty,
    );

    expect(find.text('Contrôle enregistré'), findsOneWidget);
    expect(find.text('Hors ligne · 3 en attente'), findsOneWidget);
    expect(h.server.inspections, isEmpty);

    h.server.online = true;
    h.online.add(true);
    await settle(tester);
    await settleSynced(tester, h);

    expect(h.server.inspections, [
      {'lotId': 'lot-1', 'controlPlanId': 'cp-1', 'point': 'RECEPTION'},
    ]);
    expect(h.server.results['insp-1'], [
      {
        'characteristic': 'DIAM',
        'values': [12.01, 12.05],
        'ok': false,
        'instrument': 'PAC-0142',
      },
      {
        'characteristic': 'ASP',
        'values': [true],
        'ok': true,
      },
    ]);
    expect(h.server.attachments, ['/inspections/insp-1/attachments']);
    expect(h.server.decisions, isEmpty);
    expect(h.server.keys.toSet(), hasLength(h.server.keys.length));
  });

  testWidgets('lot inconnu hors ligne : message', (tester) async {
    final h = await pumpM2(tester, online: true);
    // Le premier cycle de synchro a mis lots et plans en cache.
    await settleUntil(
      tester,
      () async => await h.db.getCache('m3b.plans') != null,
    );
    h.server.online = false;
    h.online.add(false);
    await settle(tester);
    await tap(tester, 'controlButton');
    await tester.enterText(find.byKey(const Key('lotNumber')), 'INCONNU');
    await tap(tester, 'lotSearch');
    await settleUntil(
      tester,
      () => find.byKey(const Key('controlMessage')).evaluate().isNotEmpty,
    );
    expect(find.text('Lot introuvable sur le téléphone.'), findsOneWidget);
  });
}
