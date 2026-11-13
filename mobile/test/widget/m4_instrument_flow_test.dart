import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'm2_flow_test.dart'
    show pumpM2, settle, settleUntil, tap, shot, loadFonts, shotDir;

void main() {
  setUpAll(() async {
    if (shotDir != null) await loadFonts();
  });

  testWidgets('scan d\'instrument hors ligne : fiche et refus si bloqué', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    await settleUntil(
      tester,
      () async => await h.db.getCache('m4.instruments') != null,
    );
    h.server.online = false;
    h.online.add(false);
    await settle(tester);

    await tap(tester, 'instrumentButton');
    await tap(tester, 'scanInstrument');
    expect(find.text('PAC-031 · Micromètre 0-25 mm'), findsOneWidget);
    expect(find.text('Statut : En service'), findsOneWidget);
    expect(find.text('Échéance 28/11/2026 (J-15)'), findsOneWidget);
    expect(find.byKey(const Key('instrumentOk')), findsOneWidget);

    h.server.instrumentQr = 'essor://instrument/ins-42';
    await tap(tester, 'scanInstrument');
    expect(find.text('Statut : Bloqué'), findsOneWidget);
    expect(
      find.text('Instrument bloqué : il ne peut pas être utilisé.'),
      findsOneWidget,
    );
    await shot(tester, 'instrument-bloque');

    h.server.instrumentQr = 'essor://instrument/ins-50';
    await tap(tester, 'scanInstrument');
    expect(find.text('Étalonnage échu depuis le 12/11/2026'), findsOneWidget);
    expect(find.byKey(const Key('instrumentBlocked')), findsOneWidget);

    h.server.instrumentQr = 'essor://instrument/inconnu';
    await tap(tester, 'scanInstrument');
    expect(find.text('Instrument inconnu sur le téléphone.'), findsOneWidget);
  });

  testWidgets('contrôle : instrument bloqué scanné refusé, valide accepté', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    await settleUntil(
      tester,
      () async => await h.db.getCache('m4.instruments') != null,
    );
    h.server.online = false;
    h.online.add(false);
    await settle(tester);

    await tap(tester, 'controlButton');
    await tap(tester, 'scanLot');
    await settleUntil(
      tester,
      () => find.byKey(const Key('plan-cp-1')).evaluate().isNotEmpty,
    );
    await tap(tester, 'plan-cp-1');
    await tester.enterText(find.byKey(const Key('value-0')), '12,01');
    await tester.enterText(find.byKey(const Key('value-1')), '12,00');
    await settle(tester);

    h.server.instrumentQr = 'essor://instrument/ins-42';
    await tap(tester, 'instrumentScan');
    expect(
      find.text('Instrument bloqué : il ne peut pas être utilisé.'),
      findsOneWidget,
    );
    expect(find.text('Aucun instrument choisi (obligatoire)'), findsOneWidget);
    await shot(tester, 'controle-instrument-refuse');

    h.server.instrumentQr = 'essor://instrument/ins-31';
    await tap(tester, 'instrumentScan');
    expect(find.text('Instrument : PAC-031'), findsOneWidget);
    expect(find.byKey(const Key('instrumentRefusal')), findsNothing);
  });
}
