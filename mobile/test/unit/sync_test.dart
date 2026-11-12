import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';

class RecordingUploader implements OperationUploader {
  final sent = <String>[];
  String? failOn;

  @override
  Future<void> upload(PendingOperation op) async {
    if (op.kind == failOn) throw const RetryableSyncException('hors ligne');
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

  test('la file conserve l\'ordre et compte les opérations', () async {
    await db.enqueue('a', '{}');
    await db.enqueue('b', '{}');
    expect((await db.pending()).map((o) => o.kind), ['a', 'b']);
    expect(await db.watchPendingCount().first, 2);
  });

  test('synchro : envoie dans l\'ordre et vide la file', () async {
    await db.enqueue('a', '{}');
    await db.enqueue('b', '{}');
    final r = await service.syncNow();
    expect(r.sent, 2);
    expect(uploader.sent, ['a', 'b']);
    expect(await db.pending(), isEmpty);
  });

  test('erreur : s\'arrête, garde l\'opération et note la tentative', () async {
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
  });

  test('uploader M0 : rien n\'est perdu', () async {
    final s = SyncService(db, const NotYetAvailableUploader());
    await db.enqueue('nc.signaler', '{}');
    await s.syncNow();
    expect(await db.pending(), hasLength(1));
  });

  test('pas de synchro concurrente', () async {
    await db.enqueue('a', '{}');
    final first = service.syncNow();
    final second = await service.syncNow();
    expect(second.sent, 0);
    expect((await first).sent, 1);
  });
}
