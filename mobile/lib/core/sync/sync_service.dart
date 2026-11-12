import 'package:essor_mobile/core/sync/sync_database.dart';

/// Envoi d'une opération au serveur.
abstract interface class OperationUploader {
  /// Lève [RetryableSyncException] si l'envoi doit être retenté plus tard,
  /// [ConflictSyncException] si l'opération est devenue impossible (409).
  Future<void> upload(PendingOperation op);
}

/// Réseau absent, serveur indisponible : on retentera, dans le même ordre.
class RetryableSyncException implements Exception {
  const RetryableSyncException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Refus définitif du serveur : l'opération passe « à revoir ».
class ConflictSyncException implements Exception {
  const ConflictSyncException(this.message);
  final String message;
  @override
  String toString() => message;
}

class SyncReport {
  const SyncReport({required this.sent, required this.failed, this.review = 0});
  final int sent;
  final int failed;
  final int review;
}

/// Vide la file locale dans l'ordre. Une erreur réseau arrête l'envoi
/// (l'ordre est préservé, reprise au prochain passage) ; un conflit marque
/// l'opération « à revoir » sans bloquer les suivantes.
class SyncService {
  SyncService(this._db, this._uploader);
  final SyncDatabase _db;
  final OperationUploader _uploader;

  /// Verrou par base : deux services (ex. après reconnexion) ne vident
  /// jamais la même file en parallèle.
  static final _running = Expando<bool>('syncRunning');

  bool get isRunning => _running[_db] ?? false;

  Future<SyncReport> syncNow() async {
    if (isRunning) return const SyncReport(sent: 0, failed: 0);
    _running[_db] = true;
    var sent = 0;
    var review = 0;
    try {
      for (final op in await _db.pending(limit: 500)) {
        try {
          await _uploader.upload(op);
        } on RetryableSyncException catch (e) {
          await _db.markFailed(op.id, e.message);
          return SyncReport(sent: sent, failed: 1, review: review);
        } on ConflictSyncException catch (e) {
          await _db.markReview(op.id, e.message);
          review++;
          continue;
        }
        await _db.markSent(op.id);
        sent++;
      }
      return SyncReport(sent: sent, failed: 0, review: review);
    } finally {
      _running[_db] = false;
    }
  }
}
