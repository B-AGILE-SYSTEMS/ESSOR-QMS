import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/sync/api_uploader.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';

import '../fakes.dart';

class RecordingUploader implements OperationUploader {
  final sent = <String>[];
  String? failOn;
  String? conflictOn;

  @override
  Future<void> upload(PendingOperation op) async {
    if (op.kind == failOn) throw const RetryableSyncException('hors ligne');
    if (op.kind == conflictOn) {
      throw const ConflictSyncException('Transition impossible');
    }
    sent.add(op.kind);
  }
}

void main() {
  late SyncDatabase db;
  late RecordingUploader uploader;
  late SyncService service;

  setUp(() {
    db = SyncDatabase(NativeDatabase.memory());
    uploader = RecordingUploader();
    service = SyncService(db, uploader);
  });

  tearDown(() => db.close());

  group('file d\'opérations', () {
    test('conserve l\'ordre et compte les opérations en attente', () async {
      await db.enqueue('a', '{}');
      await db.enqueue('b', '{}');
      expect((await db.pending()).map((o) => o.kind), ['a', 'b']);
      expect(await db.watchPendingCount().first, 2);
    });

    test('envoie dans l\'ordre et marque « envoyé »', () async {
      await db.enqueue('a', '{}');
      await db.enqueue('b', '{}');
      final r = await service.syncNow();
      expect(r.sent, 2);
      expect(uploader.sent, ['a', 'b']);
      expect(await db.pending(), isEmpty);
      final all = await db.watchAll().first;
      expect(all.map((o) => o.state), everyElement(OpState.sent));
    });

    test(
      'erreur réseau : s\'arrête, garde l\'ordre, reprend ensuite',
      () async {
        await db.enqueue('a', '{}');
        await db.enqueue('b', '{}');
        await db.enqueue('c', '{}');
        uploader.failOn = 'b';

        final r = await service.syncNow();
        expect(r.sent, 1);
        expect(r.failed, 1);
        final rest = await db.pending();
        expect(rest.map((o) => o.kind), ['b', 'c']);
        expect(rest.first.attempts, 1);
        expect(rest.first.lastError, 'hors ligne');

        uploader.failOn = null;
        final again = await service.syncNow();
        expect(again.sent, 2);
        expect(uploader.sent, ['a', 'b', 'c']);
      },
    );

    test('409 : « à revoir » avec le message, sans bloquer la suite', () async {
      await db.enqueue('a', '{}');
      await db.enqueue('t', '{}');
      await db.enqueue('c', '{}');
      uploader.conflictOn = 't';

      final r = await service.syncNow();

      expect(r.sent, 2);
      expect(r.review, 1);
      expect(uploader.sent, ['a', 'c']);
      expect(await db.watchReviewCount().first, 1);
      final t = (await db.watchAll().first).firstWhere((o) => o.kind == 't');
      expect(t.state, OpState.review);
      expect(t.lastError, 'Transition impossible');
      // Une opération « à revoir » n'est pas renvoyée.
      await service.syncNow();
      expect(uploader.sent, ['a', 'c']);
    });

    test('pas de synchro concurrente', () async {
      await db.enqueue('a', '{}');
      final first = service.syncNow();
      final second = await service.syncNow();
      expect(second.sent, 0);
      expect((await first).sent, 1);
    });
  });

  group('envoi à l\'API (simulée)', () {
    late FakeAdapter adapter;
    late SyncService apiSync;
    var failNetwork = false;

    setUp(() {
      failNetwork = false;
      adapter = FakeAdapter((o) {
        if (failNetwork) {
          throw DioException.connectionError(
            requestOptions: o,
            reason: 'pas de réseau',
          );
        }
        if (o.path == '/records') return (201, {'id': 'srv-1'});
        if (o.path.contains('/transitions/cloturer')) {
          return (
            409,
            {'error': 'conflict', 'message': 'Transition impossible : CLOS'},
          );
        }
        return (200, {'id': 'x'});
      });
      final dio = Dio()..httpClientAdapter = adapter;
      apiSync = SyncService(db, ApiOperationUploader(dio, db));
    });

    test('même Idempotency-Key à chaque renvoi (pas de doublon)', () async {
      final queue = OperationQueue(db);
      await queue.createRecord(
        type: 'INCIDENT',
        siteId: 's1',
        title: 'Presqu\'accident',
        occurredAt: DateTime.utc(2026, 11, 12, 9, 41),
      );
      failNetwork = true;
      await apiSync.syncNow();
      failNetwork = false;
      await apiSync.syncNow();

      expect(adapter.requests, hasLength(2));
      final keys = adapter.requests
          .map((r) => r.headers['Idempotency-Key'])
          .toSet();
      expect(keys, hasLength(1));
      expect(keys.single, matches(RegExp(r'^[0-9a-f-]{36}$')));
      final body = adapter.requests.last.data as Map<String, dynamic>;
      expect(body['clientId'], isNotEmpty);
      expect(body['occurredAt'], '2026-11-12T09:41:00.000Z');
    });

    test('chaque opération a sa propre clé', () async {
      final queue = OperationQueue(db);
      await queue.transition(recordId: 'r1', code: 'demarrer');
      await queue.transition(recordId: 'r1', code: 'terminer');
      final keys = (await db.pending()).map((o) => o.idempotencyKey).toSet();
      expect(keys, hasLength(2));
    });

    test('photo d\'une fiche locale : envoyée sur l\'id serveur', () async {
      final dir = await Directory.systemTemp.createTemp('essor');
      addTearDown(() => dir.delete(recursive: true));
      final photo = File('${dir.path}/p.jpg')..writeAsBytesSync([1, 2, 3]);
      await OperationQueue(db).createRecord(
        type: 'INCIDENT',
        siteId: 's1',
        title: 't',
        occurredAt: DateTime.now(),
        photoPaths: [photo.path],
      );

      final r = await apiSync.syncNow();

      expect(r.sent, 2);
      expect(adapter.requests.map((o) => o.path), [
        '/records',
        '/records/srv-1/attachments',
      ]);
      expect(adapter.requests.last.data, isA<FormData>());
    });

    test('409 serveur : message français conservé', () async {
      await OperationQueue(db).transition(recordId: 'r1', code: 'cloturer');
      await OperationQueue(db).transition(recordId: 'r1', code: 'terminer');
      final r = await apiSync.syncNow();
      expect(r.review, 1);
      expect(r.sent, 1);
      final op = (await db.watchAll().first).last;
      expect(op.lastError, 'Transition impossible : CLOS');
    });

    test('classement des erreurs', () {
      DioException err(int status) => DioException.badResponse(
        statusCode: status,
        requestOptions: RequestOptions(),
        response: Response(
          requestOptions: RequestOptions(),
          statusCode: status,
          data: jsonDecode('{"message":"m"}'),
        ),
      );
      expect(classify(err(409)), isA<ConflictSyncException>());
      expect(classify(err(422)), isA<ConflictSyncException>());
      expect(classify(err(503)), isA<RetryableSyncException>());
      expect(classify(err(401)), isA<RetryableSyncException>());
      expect(classify(err(413)), isA<ConflictSyncException>());
      final inProgress = DioException.badResponse(
        statusCode: 409,
        requestOptions: RequestOptions(),
        response: Response(
          requestOptions: RequestOptions(),
          statusCode: 409,
          data: {'error': 'idempotency_in_progress', 'message': 'm'},
        ),
      );
      expect(classify(inProgress), isA<RetryableSyncException>());
    });

    test('date terrain jamais dans le futur du téléphone', () {
      final now = DateTime.utc(2026, 11, 12, 10);
      final future = {'occurredAt': '2026-11-12T10:30:00Z'};
      final past = {'occurredAt': '2026-11-12T09:00:00Z'};
      expect(
        clampOccurredAt(future, now)['occurredAt'],
        '2026-11-12T10:00:00.000Z',
      );
      expect(clampOccurredAt(past, now), same(past));
    });
  });
}
