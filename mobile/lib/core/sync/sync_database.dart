import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';

part 'sync_database.g.dart';

/// État d'une opération de la file.
abstract final class OpState {
  static const pending = 'en_attente';
  static const sent = 'envoye';
  static const review = 'a_revoir';
}

/// File d'attente locale des opérations à envoyer au serveur.
class PendingOperations extends Table {
  IntColumn get id => integer().autoIncrement()();

  /// `record.create`, `record.transition` ou `attachment.upload`.
  TextColumn get kind => text().withLength(min: 1, max: 64)();

  /// Corps JSON de l'opération.
  TextColumn get payload => text()();
  DateTimeColumn get createdAt => dateTime()();
  IntColumn get attempts => integer().withDefault(const Constant(0))();
  TextColumn get lastError => text().nullable()();

  /// En-tête `Idempotency-Key` (UUID généré sur le téléphone).
  TextColumn get idempotencyKey => text().withDefault(const Constant(''))();

  /// [OpState].
  TextColumn get state => text().withDefault(const Constant(OpState.pending))();
}

/// Correspondance identifiant local (clientId) → identifiant serveur.
class IdMappings extends Table {
  TextColumn get clientId => text()();
  TextColumn get serverId => text()();
  @override
  Set<Column> get primaryKey => {clientId};
}

/// Cache JSON clé → valeur (mes tâches, dernier horodatage serveur…).
class CacheEntries extends Table {
  TextColumn get key => text()();
  TextColumn get value => text()();
  @override
  Set<Column> get primaryKey => {key};
}

@DriftDatabase(tables: [PendingOperations, IdMappings, CacheEntries])
class SyncDatabase extends _$SyncDatabase {
  SyncDatabase([QueryExecutor? executor])
    : super(executor ?? driftDatabase(name: 'essor_sync'));

  @override
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onUpgrade: (m, from, to) async {
      if (from < 2) {
        await m.addColumn(pendingOperations, pendingOperations.idempotencyKey);
        await m.addColumn(pendingOperations, pendingOperations.state);
        await m.createTable(idMappings);
        await m.createTable(cacheEntries);
      }
    },
  );

  Future<int> enqueue(
    String kind,
    String payload, {
    String idempotencyKey = '',
    DateTime? now,
  }) => into(pendingOperations).insert(
    PendingOperationsCompanion.insert(
      kind: kind,
      payload: payload,
      createdAt: now ?? DateTime.now(),
      idempotencyKey: Value(idempotencyKey),
    ),
  );

  /// Opérations en attente dans l'ordre d'arrivée (FIFO).
  Future<List<PendingOperation>> pending({int limit = 50}) =>
      (select(pendingOperations)
            ..where((t) => t.state.equals(OpState.pending))
            ..orderBy([(t) => OrderingTerm.asc(t.id)])
            ..limit(limit))
          .get();

  /// Toutes les opérations, les plus récentes d'abord (écran d'état).
  Stream<List<PendingOperation>> watchAll({int limit = 100}) =>
      (select(pendingOperations)
            ..orderBy([(t) => OrderingTerm.desc(t.id)])
            ..limit(limit))
          .watch();

  Stream<int> watchPendingCount() => _watchCount(OpState.pending);
  Stream<int> watchReviewCount() => _watchCount(OpState.review);

  Stream<int> _watchCount(String state) {
    final count = pendingOperations.id.count();
    return (selectOnly(pendingOperations)
          ..addColumns([count])
          ..where(pendingOperations.state.equals(state)))
        .map((row) => row.read(count) ?? 0)
        .watchSingle();
  }

  Future<void> remove(int id) =>
      (delete(pendingOperations)..where((t) => t.id.equals(id))).go();

  Future<void> markSent(int id) =>
      (update(pendingOperations)..where((t) => t.id.equals(id))).write(
        const PendingOperationsCompanion(
          state: Value(OpState.sent),
          lastError: Value(null),
        ),
      );

  Future<void> markReview(int id, String message) =>
      (update(pendingOperations)..where((t) => t.id.equals(id))).write(
        PendingOperationsCompanion(
          state: const Value(OpState.review),
          lastError: Value(message),
        ),
      );

  Future<void> markFailed(int id, String error) =>
      (update(pendingOperations)..where((t) => t.id.equals(id))).write(
        PendingOperationsCompanion.custom(
          attempts: pendingOperations.attempts + const Constant(1),
          lastError: Variable(error),
        ),
      );

  Future<void> saveMapping(String clientId, String serverId) => into(
    idMappings,
  ).insertOnConflictUpdate(IdMapping(clientId: clientId, serverId: serverId));

  Future<String?> serverIdFor(String clientId) async => (await (select(
    idMappings,
  )..where((t) => t.clientId.equals(clientId))).getSingleOrNull())?.serverId;

  Future<void> putCache(String key, String value) =>
      into(cacheEntries)
          .insertOnConflictUpdate(CacheEntry(key: key, value: value));

  Future<String?> getCache(String key) async => (await (select(
    cacheEntries,
  )..where((t) => t.key.equals(key))).getSingleOrNull())?.value;

  Stream<String?> watchCache(String key) => (select(
    cacheEntries,
  )..where((t) => t.key.equals(key))).watchSingleOrNull().map((e) => e?.value);
}
