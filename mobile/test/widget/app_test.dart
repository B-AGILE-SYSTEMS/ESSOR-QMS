import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/app.dart';
import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/api/profile_cache.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

import '../fake_server.dart';
import '../fakes.dart';

Future<ProviderContainer> pumpApp(
  WidgetTester tester, {
  required MemoryStore store,
  bool online = false,
  Responder? respond,
}) async {
  final db = SyncDatabase(NativeDatabase.memory());
  final container = ProviderContainer(
    overrides: [
      secureStoreProvider.overrideWithValue(store),
      oidcClientProvider.overrideWithValue(FakeOidc()),
      syncDatabaseProvider.overrideWithValue(db),
      onlineProvider.overrideWith((ref) => Stream.value(online)),
      apiClientFactoryProvider.overrideWith(
        (ref) => (server) {
          final dio = Dio()
            ..httpClientAdapter = FakeAdapter(respond ?? FakeServer().respond);
          return ApiClient(server, ref.read(authServiceProvider), dio: dio);
        },
      ),
    ],
  );
  addTearDown(() async {
    container.dispose();
    await db.close();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(container: container, child: const EssorApp()),
  );
  await tester.pumpAndSettle();
  return container;
}

MemoryStore loggedInStore() => MemoryStore()
  ..values[ServerConfigRepository.key] = 'https://qms.local'
  ..values[AuthService.accessKey] = 'tok';

void main() {
  testWidgets('premier lancement : serveur puis connexion puis accueil', (
    tester,
  ) async {
    final store = MemoryStore();
    await pumpApp(tester, store: store);

    expect(find.text('Adresse du serveur de votre entreprise'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('serverUrl')), 'pas une url');
    await tester.tap(find.byKey(const Key('serverSubmit')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Adresse invalide'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('serverUrl')),
      'https://qms.local',
    );
    await tester.tap(find.byKey(const Key('serverSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('Serveur : https://qms.local'), findsOneWidget);

    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    expect(find.text('Bonjour Youssef'), findsOneWidget);
    expect(store.values[AuthService.accessKey], 'a1');
  });

  testWidgets('accueil : apparence, gros boutons et indicateur hors ligne', (
    tester,
  ) async {
    await pumpApp(tester, store: loggedInStore());

    expect(find.text('Kénitra · Atelier usinage'), findsOneWidget);
    expect(find.text('Hors ligne'), findsOneWidget);
    final ctx = tester.element(find.byKey(const Key('reportButton')));
    expect(Theme.of(ctx).colorScheme.primary, const Color(0xFF1D4ED8));

    for (final k in ['reportButton', 'scanButton']) {
      final size = tester.getSize(find.byKey(Key(k)));
      expect(size.height, greaterThanOrEqualTo(64), reason: k);
    }
    // Boutons en bas de l'écran, sous la liste.
    final screen = tester.view.physicalSize / tester.view.devicePixelRatio;
    expect(
      tester.getBottomLeft(find.byKey(const Key('scanButton'))).dy,
      greaterThan(screen.height * 0.8),
    );
  });

  testWidgets('accueil : « Signaler » ouvre l\'étape 1', (tester) async {
    await pumpApp(tester, store: loggedInStore());

    await tester.tap(find.byKey(const Key('reportButton')));
    await tester.pumpAndSettle();

    expect(find.text('Quel type de problème ?'), findsOneWidget);
    expect(find.text('Étape 1 sur 3'), findsOneWidget);
  });

  testWidgets('déconnexion : retour à l\'écran de connexion', (tester) async {
    final store = loggedInStore();
    await pumpApp(tester, store: store);
    await tester.tap(find.byTooltip('Se déconnecter'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(store.values.containsKey(AuthService.accessKey), isFalse);
  });

  testWidgets('sans réseau : accueil avec le dernier profil connu', (
    tester,
  ) async {
    final store = loggedInStore();
    final first = await pumpApp(tester, store: store);
    expect(store.values.containsKey(ProfileCache.key), isTrue);
    first.dispose();

    await pumpApp(
      tester,
      store: store,
      respond: (o) => throw const SocketException('pas de réseau'),
    );
    expect(find.text('Bonjour Youssef'), findsOneWidget);
    expect(find.text('Hors ligne'), findsOneWidget);
    final ctx = tester.element(find.byKey(const Key('reportButton')));
    expect(Theme.of(ctx).colorScheme.primary, const Color(0xFF1D4ED8));
    expect(store.values[AuthService.accessKey], 'tok');
  });

  testWidgets('sans réseau ni cache : retour à la connexion', (tester) async {
    await pumpApp(
      tester,
      store: loggedInStore(),
      respond: (o) => throw const SocketException('pas de réseau'),
    );
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(find.text('Profil indisponible. Réessayez.'), findsOneWidget);
  });

  testWidgets('jeton révoqué (401) : session et cache effacés', (tester) async {
    final store = loggedInStore();
    final first = await pumpApp(tester, store: store);
    first.dispose();

    await pumpApp(tester, store: store, respond: (o) => (401, null));
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(find.text('Session expirée. Reconnectez-vous.'), findsOneWidget);
    expect(store.values.containsKey(AuthService.accessKey), isFalse);
    expect(store.values.containsKey(ProfileCache.key), isFalse);
  });

  test('libellés de l\'indicateur', () {
    expect(syncLabel(online: true, pending: 0), 'Synchronisé');
    expect(syncLabel(online: true, pending: 3), 'Synchro · 3 en attente');
    expect(syncLabel(online: false, pending: 2), 'Hors ligne · 2 en attente');
  });

  test('redirection selon la session', () {
    final s = ServerUrl.parse('https://x.local')!;
    expect(redirectFor(const NeedsServer(), '/'), '/serveur');
    expect(redirectFor(NeedsLogin(s), '/connexion'), isNull);
  });
}
