import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/widgets/terrain.dart';

import 'm2_flow_test.dart'
    show
        Harness,
        pumpM2,
        settle,
        settleSynced,
        tap,
        tapPhoto,
        shot,
        loadFonts,
        shotDir,
        expectTargets;

Future<void> goOffline(Harness h, WidgetTester tester) async {
  h.server.online = false;
  h.online.add(false);
  await settle(tester);
}

Future<void> goOnline(Harness h, WidgetTester tester, {int pending = 0}) async {
  h.server.online = true;
  h.online.add(true);
  await settle(tester);
  await settleSynced(tester, h, pending: pending);
}

void main() {
  setUpAll(() async {
    if (shotDir != null) await loadFonts();
  });

  testWidgets('mes audits : seuls les audits à exécuter, en cache', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    expect(h.server.lastAuditQuery, {
      'auditor': 'me',
      'status': 'PLANIFIE,EN_COURS',
    });
    await goOffline(h, tester);
    await tap(tester, 'auditsButton');
    expect(find.text('Mes audits'), findsOneWidget);
    expect(find.byKey(const Key('audit-aud-1')), findsOneWidget);
    expect(find.byKey(const Key('audit-aud-9')), findsNothing);
    expect(find.textContaining('3 questions'), findsOneWidget);
    await shot(tester, 'mes-audits');
  });

  testWidgets('exécution hors ligne question par question puis synchro', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    await goOffline(h, tester);
    await tap(tester, 'auditsButton');
    await tap(tester, 'audit-aud-1');

    expect(find.text('Question 1 sur 3'), findsOneWidget);
    expect(find.text('§6.1.2 · Identification des dangers'), findsOneWidget);
    expectTargets(tester, find.byType(OptionButton));
    await tap(tester, 'ans-conforme');
    await tap(tester, 'auditNext');

    expect(find.text('Question 2 sur 3'), findsOneWidget);
    await tap(tester, 'ans-ecart_majeur');
    expect(find.byKey(const Key('gapNote')), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('answerComment')),
      'Palettes dans l\'allée B',
    );
    await tapPhoto(tester, 'answerPhoto', 1);
    expect(find.text('Photo (1)'), findsOneWidget);
    await shot(tester, 'audit-question-hors-ligne');
    await tap(tester, 'auditNext');

    await tap(tester, 'ans-na');
    await tap(tester, 'auditNext'); // Terminer
    expect(find.text('Mes audits'), findsOneWidget);
    expect(find.textContaining('3 répondues'), findsOneWidget);
    expect(find.text('Hors ligne · 5 en attente'), findsOneWidget);
    expect(h.server.answers, isEmpty);

    await goOnline(h, tester);
    expect(h.server.answers.keys, ['q1', 'q2', 'q3']);
    expect(h.server.answers['q2'], {
      'result': 'ecart_majeur',
      'comment': 'Palettes dans l\'allée B',
      'evidence': null,
    });
    expect(h.server.attachments, ['/records/aud-1/attachments']);
    expect(h.server.transitions, ['/records/aud-1/transitions/demarrer']);
    expect(h.server.keys.toSet(), hasLength(5));
    expect(find.text('Synchronisé'), findsOneWidget);
  });

  testWidgets('reprise après coupure : même clé, aucun doublon', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    await goOffline(h, tester);
    await tap(tester, 'auditsButton');
    await tap(tester, 'audit-aud-1');
    await tap(tester, 'ans-conforme');
    await tap(tester, 'auditNext');
    await tap(tester, 'ans-observation');
    await tap(tester, 'auditNext');

    // Le réseau revient puis tombe après la première réponse.
    h.server.cutAfterWrites = 1;
    await goOnline(h, tester, pending: 1);
    expect(h.server.answers.keys, ['q1']);
    expect(await tester.runAsync(() => h.db.watchPendingCount().first), 1);

    await goOffline(h, tester);
    h.server.cutAfterWrites = null;
    await goOnline(h, tester);
    expect(h.server.answers.keys, ['q1', 'q2']);
    expect(h.server.answers['q2']?['result'], 'observation');
    final keys = h.server.keys;
    expect(h.server.transitions, ['/records/aud-1/transitions/demarrer']);
    expect(keys.toSet(), hasLength(3), reason: 'une clé par opération');
  });

  testWidgets('document lu hors ligne, accusé de lecture différé', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    expect(h.server.downloads, 1);
    await goOffline(h, tester);

    await tap(tester, 'documentsButton');
    expect(find.text('Documents en vigueur'), findsOneWidget);
    expect(find.text('Disponible hors ligne · À lire'), findsOneWidget);
    await tap(tester, 'open-doc-1');
    expect(h.server.opened, hasLength(1));
    await tap(tester, 'ack-doc-1');
    expect(find.text('Disponible hors ligne · Lu ✓'), findsOneWidget);
    expect(find.text('Hors ligne · 1 en attente'), findsOneWidget);
    await shot(tester, 'documents-hors-ligne');
    expect(h.server.acks, isEmpty);

    await goOnline(h, tester);
    expect(h.server.acks, ['doc-1/3']);
    // Le serveur n'a pas encore l'accusé dans sa liste : il reste acquis.
    expect(find.text('Disponible hors ligne · Lu ✓'), findsOneWidget);
    expect(h.server.downloads, 1, reason: 'pas de re-téléchargement');
  });
}
