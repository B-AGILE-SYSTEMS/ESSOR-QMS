import 'dart:convert';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/m3b/controls_repository.dart';
import 'package:essor_mobile/core/m3b/gs1.dart';
import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

import '../fake_server.dart';

void main() {
  late SyncDatabase db;
  late ControlsRepository repo;
  setUp(() async {
    db = SyncDatabase(NativeDatabase.memory());
    repo = ControlsRepository(db, OperationQueue(db));
    await db.putCache(lotsCacheKey, jsonEncode(FakeServer().lots));
    await db.putCache(plansCacheKey, jsonEncode(FakeServer().plans));
  });
  tearDown(() => db.close());

  test('lot retrouvé hors ligne par lot fournisseur, numéro ou QR', () async {
    expect(
      (await repo.find(const Gs1Label(lot: 'ACX4471'), null))?.id,
      'lot-1',
    );
    expect(
      (await repo.find(const Gs1Label(lot: 'l2611-b'), null))?.id,
      'lot-1',
    );
    expect((await repo.find(const LotQr('lot-1'), null))?.id, 'lot-1');
    expect(await repo.find(const Gs1Label(lot: 'X'), null), isNull);
  });

  test('contrôle mis en file dans l\'ordre, statut local mis à jour', () async {
    final lot = (await repo.cachedLots()).single;
    final plan = (await repo.cachedPlans()).first;
    await repo.record(
      lot: lot,
      plan: plan,
      results: const [
        CharResult(characteristic: 'DIAM', values: [12.0], ok: true),
      ],
      decision: Decision.libere,
      comment: '  ',
      photoPaths: const ['/tmp/p.jpg'],
    );
    final ops = await db.pending();
    expect(
      [for (final o in ops) o.kind],
      [
        OpKind.inspectionCreate,
        OpKind.inspectionResults,
        OpKind.inspectionPhoto,
        OpKind.inspectionDecision,
      ],
    );
    final decision = jsonDecode(ops.last.payload) as Map<String, dynamic>;
    expect(decision['decision'], 'LIBERE');
    expect(decision.containsKey('comment'), isFalse);
    expect((await repo.cachedLots()).single.status, 'LIBERE');
  });
}
