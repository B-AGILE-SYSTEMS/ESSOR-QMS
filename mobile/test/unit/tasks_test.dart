import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/tasks_repository.dart';

import '../fakes.dart';

void main() {
  late SyncDatabase db;
  late TasksRepository repo;
  late FakeAdapter adapter;
  late ApiClient api;
  var tasks = <Map<String, Object?>>[];

  setUp(() {
    db = SyncDatabase(NativeDatabase.memory());
    repo = TasksRepository(db, OperationQueue(db));
    tasks = [task('a1', 'A_FAIRE'), task('a2', 'EN_COURS')];
    adapter = FakeAdapter((o) {
      if (o.path == '/my-tasks') return (200, tasks);
      if (o.path == '/sync/changes') {
        return (
          200,
          {
            'records': [task('a2', 'FAITE')],
            'serverTime': '2026-11-12T10:00:00Z',
          },
        );
      }
      return (404, null);
    });
    api = ApiClient(
      ServerUrl.parse('https://qms.local')!,
      AuthService(FakeOidc(), MemoryStore()),
      dio: Dio()..httpClientAdapter = adapter,
    );
  });

  tearDown(() => db.close());

  test('mes tâches : mises en cache pour le hors ligne', () async {
    await repo.refresh(api);
    final cached = await repo.current();
    expect(cached.map((t) => t.id), ['a1', 'a2']);
  });

  test('marquer faite hors ligne : démarrer puis terminer en file', () async {
    await repo.refresh(api);
    await repo.markDone((await repo.current()).first);

    final ops = await db.pending();
    expect(ops.map((o) => o.kind), [OpKind.transition, OpKind.transition]);
    expect(ops.map((o) => o.payload), [
      contains('demarrer'),
      contains('terminer'),
    ]);
    expect((await repo.current()).first.status, 'FAITE');
  });

  test('rafraîchissement : l\'état local en file est conservé', () async {
    await repo.refresh(api);
    await repo.markDone((await repo.current()).first);
    await repo.refresh(api);
    expect((await repo.current()).first.status, 'FAITE');
  });

  test('sync/changes : applique les changements, mémorise since', () async {
    await repo.refresh(api);
    await repo.pullChanges(api);
    expect((await repo.current()).last.status, 'FAITE');
    expect(await db.getCache(sinceCacheKey), '2026-11-12T10:00:00Z');

    await repo.pullChanges(api);
    final last = adapter.requests.last;
    expect(last.queryParameters['since'], '2026-11-12T10:00:00Z');
  });

  test('sync/changes paginé : rappelle tant que 500 fiches', () async {
    var calls = 0;
    final paged = FakeAdapter((o) {
      if (o.path == '/my-tasks') return (200, tasks);
      calls++;
      if (calls == 1) {
        return (
          200,
          {
            'records': [
              for (var i = 0; i < changesPageLimit; i++)
                task('a${i % 2 + 1}', 'EN_COURS'),
            ],
            'serverTime': 't1',
          },
        );
      }
      return (
        200,
        {
          'records': [task('a1', 'FAITE')],
          'serverTime': 't2',
        },
      );
    });
    final pagedApi = ApiClient(
      ServerUrl.parse('https://qms.local')!,
      AuthService(FakeOidc(), MemoryStore()),
      dio: Dio()..httpClientAdapter = paged,
    );
    await repo.refresh(pagedApi);
    final changed = await repo.pullChanges(pagedApi);

    expect(calls, 2);
    expect(paged.requests.last.queryParameters['since'], 't1');
    expect(changed, hasLength(2));
    expect((await repo.current()).first.status, 'FAITE');
    expect(await db.getCache(sinceCacheKey), 't2');
  });

  test('seule une action ouverte peut être marquée faite', () async {
    await repo.refresh(api);
    final all = await repo.current();
    expect(canMarkDone(all.first), isTrue);
    expect(canMarkDone(all.first.withStatus('FAITE', 'Faite')), isFalse);
  });
}

Map<String, Object?> task(String id, String status) => {
  'id': id,
  'reference': 'ACT-2026-00${id.substring(1)}',
  'type': 'ACTION',
  'status': status,
  'statusLabel': status,
  'title': 'Contrôle compteur outil $id',
  'dueDate': '2026-11-20',
  'overdue': false,
};
