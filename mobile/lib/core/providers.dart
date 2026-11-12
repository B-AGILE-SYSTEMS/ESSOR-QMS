import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';

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

final operationUploaderProvider = Provider<OperationUploader>(
  (ref) => const NotYetAvailableUploader(),
);

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
  const Authenticated(this.server, this.me, this.branding, this.logoUrl);
  final ServerUrl server;
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
    await ref.read(authServiceProvider).logout();
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
    await ref.read(authServiceProvider).logout();
    if (current is Authenticated) state = NeedsLogin(current.server);
  }

  Future<void> _loadProfile(ServerUrl server) async {
    final api = ref.read(apiClientFactoryProvider)(server);
    try {
      final results = await Future.wait([api.me(), api.branding()]);
      final branding = results[1] as Branding;
      state = Authenticated(
        server,
        results[0] as Me,
        branding,
        api.logoUri(branding),
      );
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        await ref.read(authServiceProvider).logout();
      }
      state = NeedsLogin(server, error: 'Profil indisponible. Réessayez.');
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
