import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/api/profile_cache.dart';
import 'package:essor_mobile/core/api/records.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/api_uploader.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';
import 'package:essor_mobile/core/sync/tasks_repository.dart';

final secureStoreProvider = Provider<SecureStore>(
  (ref) => const FlutterSecureStore(),
);

final serverConfigProvider = Provider<ServerConfigRepository>(
  (ref) => ServerConfigRepository(ref.watch(secureStoreProvider)),
);

final oidcClientProvider = Provider<OidcClient>(
  (ref) => const AppAuthOidcClient(),
);

final authServiceProvider = Provider<AuthService>(
  (ref) => AuthService(
    ref.watch(oidcClientProvider),
    ref.watch(secureStoreProvider),
  ),
);

final profileCacheProvider = Provider<ProfileCache>(
  (ref) => ProfileCache(ref.watch(secureStoreProvider)),
);

/// Fabrique du client API (surchargée en test).
final apiClientFactoryProvider = Provider<ApiClient Function(ServerUrl)>(
  (ref) =>
      (server) => ApiClient(server, ref.read(authServiceProvider)),
);

final syncDatabaseProvider = Provider<SyncDatabase>((ref) {
  final db = SyncDatabase();
  ref.onDispose(db.close);
  return db;
});

/// Client API de la session courante (`null` hors session).
final apiClientProvider = Provider<ApiClient?>((ref) {
  final s = ref.watch(sessionProvider);
  if (s is! Authenticated) return null;
  return ref.read(apiClientFactoryProvider)(s.server);
});

final operationUploaderProvider = Provider<OperationUploader>((ref) {
  final api = ref.watch(apiClientProvider);
  if (api == null) return const _NoSessionUploader();
  return ApiOperationUploader(api.dio, ref.watch(syncDatabaseProvider));
});

class _NoSessionUploader implements OperationUploader {
  const _NoSessionUploader();
  @override
  Future<void> upload(PendingOperation op) async =>
      throw const RetryableSyncException('Non connecté');
}

final operationQueueProvider = Provider<OperationQueue>(
  (ref) => OperationQueue(ref.watch(syncDatabaseProvider)),
);

final tasksRepositoryProvider = Provider<TasksRepository>(
  (ref) => TasksRepository(
    ref.watch(syncDatabaseProvider),
    ref.watch(operationQueueProvider),
  ),
);

final tasksProvider = StreamProvider<List<RecordSummary>>(
  (ref) => ref.watch(tasksRepositoryProvider).watch(),
);

final reviewCountProvider = StreamProvider<int>(
  (ref) => ref.watch(syncDatabaseProvider).watchReviewCount(),
);

/// Cycle complet : envoi de la file, puis `sync/changes` et « mes tâches ».
/// Les erreurs réseau sont silencieuses : on retentera au prochain cycle.
Future<void> runSyncCycle(Ref ref) async {
  // Dépendances lues avant toute attente : le fournisseur appelant
  // peut être reconstruit pendant l'envoi.
  final sync = ref.read(syncServiceProvider);
  final api = ref.read(apiClientProvider);
  final tasks = ref.read(tasksRepositoryProvider);
  await sync.syncNow();
  if (api == null) return;
  try {
    await tasks.pullChanges(api);
    await tasks.refresh(api);
  } on DioException {
    return;
  }
}

/// Cycle de synchro déclenché depuis l'interface.
final syncCycleProvider = Provider<Future<void> Function()>(
  (ref) =>
      () => runSyncCycle(ref),
);

Future<void> runSyncCycleFromWidget(WidgetRef ref) =>
    ref.read(syncCycleProvider)();

final operationsProvider = StreamProvider<List<PendingOperation>>(
  (ref) => ref.watch(syncDatabaseProvider).watchAll(),
);

/// Période de rafraîchissement au premier plan (contrat M2).
const syncPeriod = Duration(minutes: 2);

/// Synchro à l'ouverture, toutes les 2 min et au retour du réseau
/// (avec rafraîchissement du profil). Aucun push Google/Apple.
final syncSchedulerProvider = Provider<void>((ref) {
  if (ref.watch(sessionProvider) is! Authenticated) return;
  unawaited(runSyncCycle(ref));
  final timer = Timer.periodic(syncPeriod, (_) => runSyncCycle(ref));
  ref.listen<AsyncValue<bool>>(onlineProvider, (prev, next) {
    final wasOnline = prev?.value ?? false;
    if (next.value == true && !wasOnline) {
      unawaited(ref.read(sessionProvider.notifier).refreshProfile());
      unawaited(runSyncCycle(ref));
    }
  });
  ref.onDispose(timer.cancel);
});

final syncServiceProvider = Provider<SyncService>(
  (ref) => SyncService(
    ref.watch(syncDatabaseProvider),
    ref.watch(operationUploaderProvider),
  ),
);

final pendingCountProvider = StreamProvider<int>(
  (ref) => ref.watch(syncDatabaseProvider).watchPendingCount(),
);

/// `true` quand une interface réseau est disponible.
final onlineProvider = StreamProvider<bool>((ref) async* {
  final c = Connectivity();
  bool isOnline(List<ConnectivityResult> r) =>
      r.any((e) => e != ConnectivityResult.none);
  yield isOnline(await c.checkConnectivity());
  yield* c.onConnectivityChanged.map(isOnline);
});

/// État de session de l'application.
sealed class SessionState {
  const SessionState();
}

final class SessionLoading extends SessionState {
  const SessionLoading();
}

final class NeedsServer extends SessionState {
  const NeedsServer();
}

final class NeedsLogin extends SessionState {
  const NeedsLogin(this.server, {this.error});
  final ServerUrl server;
  final String? error;
}

final class Authenticated extends SessionState {
  const Authenticated(
    this.server,
    this.me,
    this.branding,
    this.logoUrl, {
    this.fromCache = false,
  });
  final ServerUrl server;

  /// `true` si le profil vient du cache (serveur injoignable au démarrage).
  final bool fromCache;
  final Me me;
  final Branding branding;
  final String? logoUrl;
}

class SessionController extends Notifier<SessionState> {
  @override
  SessionState build() {
    unawaited(_restore());
    return const SessionLoading();
  }

  Future<void> _restore() async {
    final server = await ref.read(serverConfigProvider).load();
    if (server == null) {
      state = const NeedsServer();
      return;
    }
    final token = await ref.read(authServiceProvider).accessToken();
    if (token == null) {
      state = NeedsLogin(server);
      return;
    }
    await _loadProfile(server);
  }

  Future<void> setServer(ServerUrl server) async {
    await ref.read(serverConfigProvider).save(server);
    state = NeedsLogin(server);
  }

  Future<void> changeServer() async {
    await _clearSession();
    state = const NeedsServer();
  }

  Future<void> login() async {
    final current = state;
    if (current is! NeedsLogin) return;
    final server = current.server;
    try {
      final ok = await ref.read(authServiceProvider).login(server);
      if (!ok) {
        state = NeedsLogin(server, error: 'Connexion annulée.');
        return;
      }
    } on Exception {
      state = NeedsLogin(server, error: 'Connexion impossible au serveur.');
      return;
    }
    await _loadProfile(server);
  }

  Future<void> logout() async {
    final current = state;
    await _clearSession();
    if (current is Authenticated) state = NeedsLogin(current.server);
  }

  /// Retour du réseau : recharge le profil et l'apparence.
  Future<void> refreshProfile() async {
    final current = state;
    if (current is Authenticated) await _loadProfile(current.server);
  }

  Future<void> _clearSession() async {
    await ref.read(authServiceProvider).logout();
    await ref.read(profileCacheProvider).clear();
  }

  /// Charge le profil. Sans réseau (ou serveur injoignable), reprend le
  /// dernier profil connu ; seul un 401 reçu du serveur ferme la session.
  Future<void> _loadProfile(ServerUrl server) async {
    final api = ref.read(apiClientFactoryProvider)(server);
    final cache = ref.read(profileCacheProvider);
    try {
      final results = await Future.wait([api.me(), api.branding()]);
      final me = results[0] as Me;
      final branding = results[1] as Branding;
      await cache.save(server, me, branding);
      state = Authenticated(server, me, branding, api.logoUri(branding));
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        await _clearSession();
        state = NeedsLogin(server, error: 'Session expirée. Reconnectez-vous.');
        return;
      }
      final cached = await cache.load(server);
      if (cached == null) {
        state = NeedsLogin(server, error: 'Profil indisponible. Réessayez.');
        return;
      }
      state = Authenticated(
        server,
        cached.me,
        cached.branding,
        api.logoUri(cached.branding),
        fromCache: true,
      );
    }
  }
}

final sessionProvider = NotifierProvider<SessionController, SessionState>(
  SessionController.new,
);

/// Apparence courante (par défaut tant que l'utilisateur n'est pas connecté).
final brandingProvider = Provider<Branding>((ref) {
  final s = ref.watch(sessionProvider);
  return s is Authenticated ? s.branding : Branding.fallback;
});

/// Logo de l'exploitant, mis en cache local pour l'affichage hors ligne.
final logoBytesProvider = FutureProvider<Uint8List?>((ref) async {
  final s = ref.watch(sessionProvider);
  final api = ref.watch(apiClientProvider);
  final url = s is Authenticated ? s.logoUrl : null;
  if (url == null || api == null) return null;
  final db = ref.read(syncDatabaseProvider);
  final key = 'logo:$url';
  try {
    final r = await api.dio.get<List<int>>(
      url,
      options: Options(responseType: ResponseType.bytes),
    );
    final bytes = Uint8List.fromList(r.data ?? const []);
    if (bytes.isNotEmpty) await db.putCache(key, base64Encode(bytes));
    return bytes;
  } on DioException {
    final cached = await db.getCache(key);
    return cached == null ? null : base64Decode(cached);
  }
});
