import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Stockage clé/valeur sécurisé (abstrait pour les tests).
abstract class SecureStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

class FlutterSecureStore implements SecureStore {
  const FlutterSecureStore([this._storage = const FlutterSecureStorage()]);
  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);
  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);
  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

/// URL du serveur on-premise, validée et normalisée (sans `/` final).
class ServerUrl {
  const ServerUrl._(this.base);
  final Uri base;

  /// Renvoie `null` si l'entrée n'est pas une URL http(s) absolue avec hôte.
  static ServerUrl? parse(String input) {
    final trimmed = input.trim().replaceAll(RegExp(r'/+$'), '');
    final uri = Uri.tryParse(trimmed);
    if (uri == null || !uri.hasAuthority || uri.host.isEmpty) return null;
    if (uri.scheme != 'https' && uri.scheme != 'http') return null;
    if (uri.hasQuery || uri.hasFragment) return null;
    return ServerUrl._(uri);
  }

  String get api => '$base/api';
  String get issuer => '$base/auth/realms/essor';

  @override
  String toString() => base.toString();
}

class ServerConfigRepository {
  ServerConfigRepository(this._store);
  static const key = 'essor.server_url';
  final SecureStore _store;

  Future<ServerUrl?> load() async {
    final raw = await _store.read(key);
    return raw == null ? null : ServerUrl.parse(raw);
  }

  Future<void> save(ServerUrl url) => _store.write(key, url.toString());
}
