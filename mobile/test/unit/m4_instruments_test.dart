import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/m4/instruments.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

import '../fake_server.dart';
import '../fakes.dart';

final today = DateTime(2026, 11, 13);

void main() {
  group('QR instrument', () {
    test('essor://instrument/<id> reconnu', () {
      expect(parseInstrumentQr('essor://instrument/ins-31'), 'ins-31');
      expect(parseInstrumentQr(' ESSOR://Instrument/PAC-031 '), 'PAC-031');
    });
    test('refuse le reste', () {
      for (final raw in [
        null,
        '',
        'essor://zone/L2',
        'essor://instrument/',
        'essor://instrument/a/b',
        'essor://instrument/%3Cx%3E',
        'https://instrument/ins-31',
      ]) {
        expect(parseInstrumentQr(raw), isNull, reason: raw);
      }
    });
  });

  group('règle en service et non échu', () {
    final all = [
      for (final j in FakeServer().instruments) Instrument.fromJson(j),
    ];
    test('en service, échéance future : utilisable, J-15', () {
      final i = all[0];
      expect(i.usable(today), isTrue);
      expect(i.daysLeft(today), 15);
      expect(i.site, 'Entrepôt Nord');
    });
    test('bloqué : refus', () {
      expect(
        all[1].refusal(today),
        'Instrument bloqué : il ne peut pas être utilisé.',
      );
    });
    test('échu hier : refus ; échéance du jour encore valable', () {
      expect(
        all[2].refusal(today),
        'Étalonnage échu : instrument inutilisable.',
      );
      expect(all[2].usable(DateTime(2026, 11, 12, 23, 59)), isTrue);
    });
    test('un instrument en cache devient échu avec le temps', () {
      expect(all[0].usable(DateTime(2026, 11, 29)), isFalse);
    });
    test('résultat de contrôle : instrumentId envoyé (lien M4)', () {
      const r = CharResult(
        characteristic: 'DIAM',
        values: [12.0],
        ok: true,
        instrument: 'PAC-031',
        instrumentId: 'ins-31',
      );
      expect(r.toJson()['instrumentId'], 'ins-31');
    });
  });

  group('cache hors ligne', () {
    late SyncDatabase db;
    late FakeServer server;
    late InstrumentsApi api;
    late InstrumentsRepository repo;
    setUp(() {
      db = SyncDatabase(NativeDatabase.memory());
      server = FakeServer();
      api = InstrumentsApi(
        Dio()..httpClientAdapter = FakeAdapter(server.respond),
      );
      repo = InstrumentsRepository(db);
    });
    tearDown(() => db.close());

    test('page {content} mise en cache, liste filtrée hors ligne', () async {
      await repo.refresh(api);
      server.online = false;
      expect((await repo.cached()).length, 3);
      expect([for (final i in await repo.usable(today)) i.code], ['PAC-031']);
      // liste de choix tirée de GET /instruments?selectable=true
      expect(server.selectableQueries, contains(true));
      expect((await repo.find('PAC-042', null))?.status, 'bloque');
      // Réseau coupé : le cache répond.
      expect((await repo.find('ins-31', api))?.code, 'PAC-031');
    });

    test('liste de choix serveur, échéance revérifiée hors ligne', () async {
      await repo.refresh(api);
      server.online = false;
      // PAC-050 est rendu par le serveur mais échu à la date locale.
      final later = today.add(const Duration(days: 30));
      expect([for (final i in await repo.usable(today)) i.code], ['PAC-031']);
      expect(await repo.usable(later), isEmpty);
    });

    test('en ligne, le statut frais remplace le cache', () async {
      await repo.refresh(api);
      server.instruments[0] = {...server.instruments[0], 'status': 'bloque'};
      final fresh = await repo.find('ins-31', api);
      expect(fresh?.usable(today), isFalse);
      expect((await repo.usable(today)), isEmpty);
    });
  });

  // Exemple réel du back (docs/api-exemples) dès qu'il paraît.
  final real = File('../docs/api-exemples/instrument.json');
  test('exemple réel docs/api-exemples/instrument.json', () {
    final raw = jsonDecode(real.readAsStringSync());
    final j = raw is Map<String, dynamic> && raw['instrument'] is Map
        ? raw['instrument'] as Map<String, dynamic>
        : raw as Map<String, dynamic>;
    final i = Instrument.fromJson(j);
    expect(i.id, '${j['id']}');
    expect(i.code, j['code']);
    expect(InstrumentStatus.labels.keys, contains(i.status));
    expect(i.nextDue, isNotNull);
  }, skip: real.existsSync() ? false : 'exemple pas encore publié par le back');
}
