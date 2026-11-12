import 'dart:convert';

import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/config/server_config.dart';

/// Dernier profil connu (`/api/me` + apparence), pour démarrer hors ligne.
class CachedProfile {
  const CachedProfile(this.me, this.branding);
  final Me me;
  final Branding branding;
}

/// Cache du profil dans le stockage sécurisé, lié au serveur courant.
class ProfileCache {
  ProfileCache(this._store);
  static const key = 'essor.profile_cache';
  final SecureStore _store;

  Future<void> save(ServerUrl server, Me me, Branding branding) => _store.write(
    key,
    jsonEncode({
      'server': server.toString(),
      'me': me.toJson(),
      'branding': branding.toJson(),
    }),
  );

  /// `null` si rien n'est en cache, si le cache est illisible
  /// ou s'il appartient à un autre serveur.
  Future<CachedProfile?> load(ServerUrl server) async {
    final raw = await _store.read(key);
    if (raw == null) return null;
    try {
      final j = jsonDecode(raw) as Map<String, dynamic>;
      if (j['server'] != server.toString()) return null;
      return CachedProfile(
        Me.fromJson(j['me'] as Map<String, dynamic>),
        Branding.fromJson(j['branding'] as Map<String, dynamic>),
      );
    } on Object {
      return null;
    }
  }

  Future<void> clear() => _store.delete(key);
}
