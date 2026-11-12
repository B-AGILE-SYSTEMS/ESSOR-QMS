import 'dart:convert';

import 'package:essor_mobile/core/api/api_client.dart';
import 'package:essor_mobile/core/api/records.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

const tasksCacheKey = 'my-tasks';
const sinceCacheKey = 'sync.since';

/// Taille maximale d'une page de `sync/changes` (serveur M2).
const changesPageLimit = 500;

/// Garde-fou contre une boucle infinie de pages.
const maxChangesPages = 20;

/// « Mes tâches » en cache local, modifiables hors ligne.
class TasksRepository {
  TasksRepository(this._db, this._queue);
  final SyncDatabase _db;
  final OperationQueue _queue;

  Stream<List<RecordSummary>> watch() =>
      _db.watchCache(tasksCacheKey).map(_decode);

  Future<List<RecordSummary>> current() async =>
      _decode(await _db.getCache(tasksCacheKey));

  Future<void> _save(List<RecordSummary> tasks) => _db.putCache(
    tasksCacheKey,
    jsonEncode([for (final t in tasks) t.toJson()]),
  );

  /// Recharge depuis le serveur ; garde l'état local des actions
  /// encore en file (marquées faites hors ligne).
  Future<void> refresh(ApiClient api) async {
    final remote = await api.myTasks();
    final local = {for (final t in await current()) t.id: t};
    await _save([
      for (final t in remote)
        if (local[t.id]?.status == 'FAITE' && t.status != 'FAITE')
          local[t.id]!
        else
          t,
    ]);
  }

  /// Applique `sync/changes` puis mémorise l'heure serveur.
  /// Pages de [changesPageLimit] fiches : on rappelle avec
  /// `since=serverTime` tant que la page est pleine ; dédoublonnage par `id`.
  Future<List<RecordSummary>> pullChanges(ApiClient api) async {
    final byId = <String, RecordSummary>{};
    var since = await _db.getCache(sinceCacheKey);
    for (var page = 0; page < maxChangesPages; page++) {
      final (changed, serverTime) = await api.changes(since);
      for (final c in changed) {
        byId[c.id] = c;
      }
      if (serverTime != null) {
        await _db.putCache(sinceCacheKey, serverTime);
      }
      if (changed.length < changesPageLimit ||
          serverTime == null ||
          serverTime == since) {
        break;
      }
      since = serverTime;
    }
    if (byId.isNotEmpty) {
      await _save([for (final t in await current()) byId[t.id] ?? t]);
    }
    return List.unmodifiable(byId.values);
  }

  /// Marque une action faite, hors ligne : transitions en file
  /// (`demarrer` si besoin, puis `terminer`) et cache mis à jour.
  Future<void> markDone(RecordSummary task) async {
    await _db.transaction(() async {
      if (task.status == 'A_FAIRE') {
        await _queue.transition(recordId: task.id, code: 'demarrer');
      }
      await _queue.transition(recordId: task.id, code: 'terminer');
      await _save([
        for (final t in await current())
          t.id == task.id ? t.withStatus('FAITE', 'Faite') : t,
      ]);
    });
  }

  static List<RecordSummary> _decode(String? raw) {
    if (raw == null) return const [];
    try {
      return List.unmodifiable([
        for (final j in jsonDecode(raw) as List<dynamic>)
          RecordSummary.fromJson(j as Map<String, dynamic>),
      ]);
    } on FormatException {
      return const [];
    }
  }
}

/// Une action peut être marquée faite depuis le terrain.
bool canMarkDone(RecordSummary t) =>
    t.type == 'ACTION' && (t.status == 'A_FAIRE' || t.status == 'EN_COURS');
