import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/theme/app_theme.dart';

import '../fakes.dart';

void main() {
  group('ServerUrl', () {
    test('normalise et dérive les URL API et Keycloak', () {
      final u = ServerUrl.parse(' https://qms.usine.local/ ')!;
      expect(u.api, 'https://qms.usine.local/api');
      expect(u.issuer, 'https://qms.usine.local/auth/realms/essor');
    });

    test('refuse les entrées invalides', () {
      for (final bad in [
        '',
        'qms.local',
        'ftp://x',
        'https://',
        'https://x?a=1',
      ]) {
        expect(ServerUrl.parse(bad), isNull, reason: bad);
      }
    });

    test('est persistée dans le stockage sécurisé', () async {
      final store = MemoryStore();
      final repo = ServerConfigRepository(store);
      await repo.save(ServerUrl.parse('http://10.0.0.5:8080')!);
      expect((await repo.load()).toString(), 'http://10.0.0.5:8080');
    });
  });

  group('AuthService', () {
    late MemoryStore store;
    late FakeOidc oidc;
    late AuthService auth;
    final server = ServerUrl.parse('https://qms.local')!;

    setUp(() {
      store = MemoryStore();
      oidc = FakeOidc();
      auth = AuthService(oidc, store);
    });

    test('stocke les jetons après connexion', () async {
      expect(await auth.login(server), isTrue);
      expect(store.values[AuthService.accessKey], 'a1');
      expect(store.values[AuthService.refreshKey], 'r1');
    });

    test('connexion annulée : aucun jeton', () async {
      oidc.next = null;
      expect(await auth.login(server), isFalse);
      expect(store.values, isEmpty);
    });

    test('renouvelle le jeton', () async {
      await auth.login(server);
      expect(await auth.refresh(server), isTrue);
      expect(await auth.accessToken(), 'a2');
    });

    test('échec du renouvellement : session effacée', () async {
      await auth.login(server);
      oidc.throwOnRefresh = true;
      expect(await auth.refresh(server), isFalse);
      expect(store.values, isEmpty);
    });
  });

  group('ApiClient', () {
    final server = ServerUrl.parse('https://qms.local')!;

    test('appelle /api/me et /api/tenant/branding avec le Bearer', () async {
      final store = MemoryStore()..values[AuthService.accessKey] = 'tok';
      final dio = Dio();
      final adapter = FakeAdapter(
        (o) => (200, o.path.endsWith('/me') ? meJson : brandingJson),
      );
      dio.httpClientAdapter = adapter;
      final api = ApiClient(server, AuthService(FakeOidc(), store), dio: dio);

      final me = await api.me();
      final b = await api.branding();

      expect(me.displayName, 'Youssef');
      expect(me.sites.single.code, 'KEN');
      expect(b.theme, AppTheme.terrain);
      expect(api.logoUri(b), 'https://qms.local/api/tenant/branding/logo');
      expect(adapter.requests.map((r) => r.uri.toString()), [
        'https://qms.local/api/me',
        'https://qms.local/api/tenant/branding',
      ]);
      expect(adapter.requests.first.headers['Authorization'], 'Bearer tok');
    });

    test('rejoue la requête après renouvellement sur 401', () async {
      final store = MemoryStore()
        ..values[AuthService.accessKey] = 'old'
        ..values[AuthService.refreshKey] = 'r';
      final dio = Dio();
      final adapter = FakeAdapter(
        (o) => o.headers['Authorization'] == 'Bearer a2'
            ? (200, meJson)
            : (401, {}),
      );
      dio.httpClientAdapter = adapter;
      final api = ApiClient(server, AuthService(FakeOidc(), store), dio: dio);

      expect((await api.me()).username, 'terrain');
      expect(adapter.requests, hasLength(2));
    });
  });

  group('Branding et thème', () {
    test('valeurs inconnues : thème clair par défaut', () {
      final b = Branding.fromJson(const {'theme': 'rose'});
      expect(b.theme, AppTheme.clair);
      expect(b.logoUrl, isNull);
    });

    test('couleur principale appliquée', () {
      final t = buildTheme(Branding.fromJson(brandingJson));
      expect(t.colorScheme.primary, const Color(0xFF1D4ED8));
      expect(t.colorScheme.onPrimary, Colors.white);
    });

    test('thème sombre et couleur invalide', () {
      final t = buildTheme(
        const Branding(primaryColor: 'rouge', theme: AppTheme.sombre),
      );
      expect(t.brightness, Brightness.dark);
      expect(t.colorScheme.primary, defaultPrimary);
    });

    test('parseHexColor', () {
      expect(parseHexColor('#ffffff'), const Color(0xFFFFFFFF));
      expect(parseHexColor('fff'), isNull);
      expect(parseHexColor(null), isNull);
    });
  });
}
