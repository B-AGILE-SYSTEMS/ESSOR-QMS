import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/app.dart';
import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/documents/documents_screen.dart';

import '../fake_server.dart';
import '../fakes.dart';

/// Captures d'écran : `SCREENSHOT_DIR=<dossier> flutter test`.
final shotDir = Platform.environment['SCREENSHOT_DIR'];
const shotKey = Key('shotRoot');

class Harness {
  Harness(this.container, this.server, this.online, this.db);
  final ProviderContainer container;
  final FakeServer server;
  final StreamController<bool> online;
  final SyncDatabase db;
}

Future<Harness> pumpM2(WidgetTester tester, {bool online = false}) async {
  tester.view.physicalSize = const Size(390, 780) * 3;
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final server = FakeServer()..online = online;
  final net = StreamController<bool>.broadcast();
  final db = SyncDatabase(NativeDatabase.memory());
  final photoDir = await tester.runAsync(
    () => Directory.systemTemp.createTemp('essor_photos'),
  );
  final photo = img.encodePng(img.Image(width: 3200, height: 2400));
  final store = MemoryStore()
    ..values[ServerConfigRepository.key] = 'https://qms.local'
    ..values[AuthService.accessKey] = 'tok';
  final container = ProviderContainer(
    overrides: [
      secureStoreProvider.overrideWithValue(store),
      oidcClientProvider.overrideWithValue(FakeOidc()),
      syncDatabaseProvider.overrideWithValue(db),
      onlineProvider.overrideWith((ref) async* {
        yield online;
        yield* net.stream;
      }),
      photoTakerProvider.overrideWithValue(() async => photo),
      photoDirProvider.overrideWith((ref) async => photoDir!),
      documentsDirProvider.overrideWith((ref) async => photoDir!),
      clockProvider.overrideWithValue(() => DateTime(2026, 11, 13)),
      fileOpenerProvider.overrideWithValue((path) async {
        server.opened.add(path);
        return File(path).existsSync();
      }),
      qrScannerProvider.overrideWithValue((_) async => 'essor://zone/L2'),
      apiClientFactoryProvider.overrideWith(
        (ref) =>
            (s) => ApiClient(
              s,
              ref.read(authServiceProvider),
              dio: Dio()..httpClientAdapter = FakeAdapter(server.respond),
            ),
      ),
    ],
  );
  addTearDown(() async {
    container.dispose();
    await net.close();
    await db.close();
    await photoDir?.delete(recursive: true);
  });
  await tester.pumpWidget(
    RepaintBoundary(
      key: shotKey,
      child: UncontrolledProviderScope(
        container: container,
        child: const EssorApp(),
      ),
    ),
  );
  await settle(tester);
  return Harness(container, server, net, db);
}

/// Laisse s'écouler les opérations réelles (SQLite, fichiers) puis l'UI.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 5; i++) {
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump(const Duration(milliseconds: 50));
  }
  await tester.pumpAndSettle();
}

Future<void> tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.tap(find.byKey(Key(key)));
  await settle(tester);
}

Future<void> loadFonts() async {
  final root = Platform.environment['FLUTTER_ROOT'] ?? '';
  final dir = '$root/bin/cache/artifacts/material_fonts';
  Future<void> load(String family, List<String> files) async {
    final loader = FontLoader(family);
    for (final f in files) {
      final file = File('$dir/$f');
      if (!file.existsSync()) return;
      loader.addFont(
        Future.value(ByteData.sublistView(file.readAsBytesSync())),
      );
    }
    await loader.load();
  }

  await load('Roboto', ['Roboto-Regular.ttf', 'Roboto-Bold.ttf']);
  await load('MaterialIcons', ['MaterialIcons-Regular.otf']);
}

Future<void> shot(WidgetTester tester, String name) async {
  final dir = shotDir;
  if (dir == null) return;
  await tester.runAsync(() async {
    final ro = tester.renderObject<RenderRepaintBoundary>(find.byKey(shotKey));
    final image = await ro.toImage(pixelRatio: 2);
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    await Directory(dir).create(recursive: true);
    await File('$dir/$name.png').writeAsBytes(data!.buffer.asUint8List());
  });
}

void expectTargets(WidgetTester tester, Finder f) {
  for (final e in f.evaluate()) {
    final size = tester.getSize(find.byWidget(e.widget));
    expect(size.height, greaterThanOrEqualTo(minTarget), reason: '$e');
  }
}

void main() {
  setUpAll(() async {
    if (shotDir != null) await loadFonts();
  });

  testWidgets('accueil : tuiles « mes tâches » et boutons en bas', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    expect(find.text('Contrôle compteur outil'), findsOneWidget);
    expect(find.text('Synchronisé'), findsOneWidget);
    expectTargets(tester, find.byType(FilledButton));
    expectTargets(tester, find.byType(OutlinedButton));
    expect(h.server.keys, isEmpty);
    // Écran d'accueil hors ligne pour la capture (maquette 1).
    h.server.online = false;
    h.online.add(false);
    await settle(tester);
    await shot(tester, 'accueil');
  });

  testWidgets('signalement hors ligne avec 2 photos puis synchro', (
    tester,
  ) async {
    final h = await pumpM2(tester, online: true);
    h.server.online = false;
    h.online.add(false);
    await settle(tester);

    await tap(tester, 'reportButton');
    await tap(tester, 'kind-presqu_accident');
    expectTargets(tester, find.byType(OptionButton));
    await tap(tester, 'reportNext');

    await tap(tester, 'scanPlace');
    expect(find.byKey(const Key('detectedZone')), findsOneWidget);
    await tap(tester, 'reportNext');

    expect(find.text('Étape 3 sur 3'), findsOneWidget);
    await tap(tester, 'takePhoto');
    await tap(tester, 'takePhoto');
    expect(find.text('Photo (2)'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('reportDescription')),
      'Palette tombée près du quai',
    );
    await tap(tester, 'danger-majeure');
    await shot(tester, 'signalement-etape3-hors-ligne');
    await tap(tester, 'reportNext');

    expect(find.text('Signalement enregistré'), findsOneWidget);
    expect(find.text('Sera envoyé dès le retour du réseau'), findsOneWidget);
    expect(find.text('Hors ligne · 3 en attente'), findsOneWidget);
    expect(h.server.created, isEmpty);

    // Retour du réseau : tout part, dans l'ordre.
    h.server.online = true;
    h.online.add(true);
    await settle(tester);
    await settle(tester);

    expect(h.server.created, hasLength(1));
    final body = h.server.created.single;
    expect(body['type'], 'INCIDENT');
    expect(body['siteId'], 's1');
    expect(body['severity'], 'majeure');
    expect(body['description'], 'Palette tombée près du quai');
    expect(body['data'], {
      'lieu': 'Kénitra · Atelier usinage · zone L2',
      'blesses': false,
      'categorie': 'presqu_accident',
    });
    expect(body['clientId'], isNotEmpty);
    expect(body['occurredAt'], isNotEmpty);
    expect(h.server.attachments, [
      '/records/srv-1/attachments',
      '/records/srv-1/attachments',
    ]);
    expect(h.server.keys.toSet(), hasLength(3));
    final left = await tester.runAsync(() => h.db.watchPendingCount().first);
    expect(left, 0);
    expect(find.text('Synchronisé'), findsOneWidget);
  });

  testWidgets('mes tâches : action marquée faite hors ligne', (tester) async {
    final h = await pumpM2(tester, online: true);
    h.server.online = false;
    h.online.add(false);
    await settle(tester);

    await tap(tester, 'tasksButton');
    expect(find.text('Mes tâches'), findsOneWidget);
    expect(find.textContaining('EN RETARD'), findsOneWidget);
    expectTargets(tester, find.byKey(const Key('done-act-1')));
    await shot(tester, 'mes-taches');

    await tap(tester, 'done-act-1');
    expect(find.byKey(const Key('done-act-1')), findsNothing);
    expect(find.text('Hors ligne · 1 en attente'), findsOneWidget);

    await tap(tester, 'openOperations');
    expect(find.text('Changement de statut — En attente'), findsOneWidget);

    h.server.online = true;
    await tap(tester, 'syncNow');
    expect(h.server.transitions, ['/records/act-1/transitions/terminer']);
    expect(find.text('Changement de statut — Envoyé'), findsOneWidget);
  });

  testWidgets('409 : opération « à revoir » visible', (tester) async {
    final h = await pumpM2(tester, online: true);
    await h.container
        .read(operationQueueProvider)
        .transition(recordId: 'x', code: 'cloturer');
    final adapterOverride = h.server;
    adapterOverride.online = true;
    await h.db.markReview(
      (await h.db.pending()).single.id,
      'Transition impossible',
    );
    await settle(tester);
    expect(find.byKey(const Key('reviewBanner')), findsOneWidget);
  });

  testWidgets('détail d\'une fiche depuis le cache hors ligne', (tester) async {
    final h = await pumpM2(tester, online: true);
    h.server.online = false;
    await tap(tester, 'tile-nc-1');
    expect(find.text('NC-2026-0418'), findsOneWidget);
    expect(find.text('Écart de cote Ø 12,05 mm'), findsOneWidget);
  });
}
