import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/m3/documents_repository.dart';
import 'package:essor_mobile/core/m3/m3_api.dart';
import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/sync/api_uploader.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

import '../fake_server.dart';
import '../fakes.dart';

void main() {
  late SyncDatabase db;
  late FakeServer server;
  late FakeAdapter adapter;
  late Dio dio;
  late Directory dir;

  setUp(() async {
    db = SyncDatabase(NativeDatabase.memory());
    server = FakeServer();
    adapter = FakeAdapter(server.respond);
    dio = Dio()..httpClientAdapter = adapter;
    dir = await Directory.systemTemp.createTemp('essor_docs');
  });

  tearDown(() async {
    await db.close();
    await dir.delete(recursive: true);
  });

  test(
    'réponse et accusé : routes et en-tête Idempotency-Key du contrat',
    () async {
      final queue = OperationQueue(db);
      await queue.auditAnswer(
        auditId: 'aud-1',
        questionId: 'q1',
        result: AuditResult.ecartMineur,
      );
      await queue.documentAck(documentId: 'doc-1', version: 'v3');
      final up = ApiOperationUploader(dio, db);
      for (final op in await db.pending()) {
        await up.upload(op);
      }
      expect(adapter.requests.map((r) => '${r.method} ${r.path}'), [
        'PUT /audits/aud-1/answers/q1',
        'POST /documents/doc-1/versions/v3/ack',
      ]);
      for (final r in adapter.requests) {
        expect(r.headers['Idempotency-Key'], isNotEmpty);
      }
      expect(server.answers['q1']?['result'], 'ecart_mineur');
    },
  );

  test(
    'nouvelle version diffusée : re-téléchargée, accusé à refaire',
    () async {
      final repo = DocumentsRepository(db, OperationQueue(db));
      final api = M3Api(dio);
      await repo.refresh(api, dir);
      final first = (await repo.current()).single;
      await repo.acknowledge(first);
      expect((await repo.current()).single.acked, isTrue);

      server.documents = [
        {
          ...server.documents.single,
          'currentVersion': {'id': 'v4', 'number': 4, 'fileName': 'PR.pdf'},
        },
      ];
      await repo.refresh(api, dir);
      final second = (await repo.current()).single;
      expect(second.version, 'v4');
      expect(second.versionLabel, 'v4');
      expect(second.acked, isFalse);
      expect(File(first.localPath!).existsSync(), isFalse);
      expect(File(second.localPath!).existsSync(), isTrue);
      expect(server.downloads, 2);
    },
  );
}
