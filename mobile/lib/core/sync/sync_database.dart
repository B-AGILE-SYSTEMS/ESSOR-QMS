import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';

part 'sync_database.g.dart';

/// File d'attente locale des opérations à envoyer au serveur.
class PendingOperations extends Table {
  IntColumn get id => integer().autoIncrement()();

  /// Type d'opération (ex. `nc.declarer`), interprété par le serveur en M2.
  TextColumn get kind => text().withLength(min: 1, max: 64)();

  /// Corps JSON de l'opération.
  TextColumn get payload => text()();
  DateTimeColumn get createdAt => dateTime()();
  IntColumn get attempts => integer().withDefault(const Constant(0))();
  TextColumn get lastError => text().nullable()();
}

@DriftDatabase(tables: [PendingOperations])
class SyncDatabase extends _$SyncDatabase {
  SyncDatabase([QueryExecutor? executor])
    : super(executor ?? driftDatabase(name: 'essor_sync'));

  @override
  int get schemaVersion => 1;

  Future<int> enqueue(String kind, String payload, {DateTime? now}) =>
      into(pendingOperations).insert(
        PendingOperationsCompanion.insert(
          kind: kind,
          payload: payload,
          createdAt: now ?? DateTime.now(),
        ),
      );

  /// Opérations dans l'ordre d'arrivée (FIFO).
  Future<List<PendingOperation>> pending({int limit = 50}) =>
      (select(pendingOperations)
            ..orderBy([(t) => OrderingTerm.asc(t.id)])
            ..limit(limit))
          .get();

  Stream<int> watchPendingCount() {
    final count = pendingOperations.id.count();
    return (selectOnly(
      pendingOperations,
    )..addColumns([count])).map((row) => row.read(count) ?? 0).watchSingle();
  }

  Future<void> remove(int id) =>
      (delete(pendingOperations)..where((t) => t.id.equals(id))).go();

  Future<void> markFailed(int id, String error) =>
      (update(pendingOperations)..where((t) => t.id.equals(id))).write(
        PendingOperationsCompanion.custom(
          attempts: pendingOperations.attempts + const Constant(1),
          lastError: Variable(error),
        ),
      );
}
