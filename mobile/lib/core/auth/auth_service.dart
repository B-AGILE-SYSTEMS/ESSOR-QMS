import 'package:flutter_appauth/flutter_appauth.dart';

import 'package:essor_mobile/core/config/server_config.dart';

const mobileClientId = 'essor-mobile';
const redirectUrl = 'ma.bagile.essor:/oauthredirect';

class Tokens {
  const Tokens({required this.accessToken, this.refreshToken});
  final String accessToken;
  final String? refreshToken;
}

/// Échange OIDC (PKCE). Abstrait pour pouvoir le simuler en test.
abstract class OidcClient {
  Future<Tokens?> authorize(ServerUrl server);
  Future<Tokens?> refresh(ServerUrl server, String refreshToken);
}

class AppAuthOidcClient implements OidcClient {
  const AppAuthOidcClient([this._appAuth = const FlutterAppAuth()]);
  final FlutterAppAuth _appAuth;

  @override
  Future<Tokens?> authorize(ServerUrl server) async {
    // flutter_appauth applique PKCE (S256) par défaut.
    final r = await _appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        mobileClientId,
        redirectUrl,
        issuer: server.issuer,
        scopes: const ['openid', 'profile', 'offline_access'],
        allowInsecureConnections: server.base.scheme == 'http',
      ),
    );
    return _toTokens(r.accessToken, r.refreshToken);
  }

  @override
  Future<Tokens?> refresh(ServerUrl server, String refreshToken) async {
    final r = await _appAuth.token(
      TokenRequest(
        mobileClientId,
        redirectUrl,
        issuer: server.issuer,
        refreshToken: refreshToken,
        allowInsecureConnections: server.base.scheme == 'http',
      ),
    );
    return _toTokens(r.accessToken, r.refreshToken);
  }

  Tokens? _toTokens(String? access, String? refresh) => access == null
      ? null
      : Tokens(accessToken: access, refreshToken: refresh);
}

/// Conserve les jetons dans le stockage sécurisé, jamais ailleurs.
class AuthService {
  AuthService(this._oidc, this._store);
  static const accessKey = 'essor.access_token';
  static const refreshKey = 'essor.refresh_token';

  final OidcClient _oidc;
  final SecureStore _store;

  Future<String?> accessToken() => _store.read(accessKey);

  Future<bool> login(ServerUrl server) async {
    final tokens = await _oidc.authorize(server);
    if (tokens == null) return false;
    await _persist(tokens);
    return true;
  }

  /// Renouvelle le jeton ; efface la session si c'est impossible.
  Future<bool> refresh(ServerUrl server) async {
    final rt = await _store.read(refreshKey);
    if (rt == null) return false;
    Tokens? tokens;
    try {
      tokens = await _oidc.refresh(server, rt);
    } on Exception {
      tokens = null;
    }
    if (tokens == null) {
      await logout();
      return false;
    }
    await _persist(tokens);
    return true;
  }

  Future<void> logout() async {
    await _store.delete(accessKey);
    await _store.delete(refreshKey);
  }

  Future<void> _persist(Tokens t) async {
    await _store.write(accessKey, t.accessToken);
    final rt = t.refreshToken;
    if (rt != null) await _store.write(refreshKey, rt);
  }
}
