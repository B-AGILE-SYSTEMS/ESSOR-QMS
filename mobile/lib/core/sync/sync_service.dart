import 'package:essor_mobile/core/sync/sync_database.dart';

/// Envoi d'une opération au serveur. Branché sur l'API en M2.
abstract interface class OperationUploader {
  /// Lève [RetryableSyncException] si l'envoi doit être retenté plus tard.
  Future<void> upload(PendingOperation op);
}

class RetryableSyncException implements Exception {
  const RetryableSyncException(this.message);
  final String message;
  @override
  String toString() => message;
}

class SyncReport {
  const SyncReport({required this.sent, required this.failed});
  final int sent;
  final int failed;
}

/// Vide la file locale dans l'ordre ; s'arrête à la première erreur
/// pour préserver l'ordre des opérations.
class SyncService {
  SyncService(this._db, this._uploader);
  final SyncDatabase _db;
  final OperationUploader _uploader;
  bool _running = false;

  bool get isRunning => _running;

  Future<SyncReport> syncNow() async {
    if (_running) return const SyncReport(sent: 0, failed: 0);
    _running = true;
    var sent = 0;
    try {
      for (final op in await _db.pending()) {
        try {
          await _uploader.upload(op);
        } on RetryableSyncException catch (e) {
          await _db.markFailed(op.id, e.message);
          return SyncReport(sent: sent, failed: 1);
        }
        await _db.remove(op.id);
        sent++;
      }
      return SyncReport(sent: sent, failed: 0);
    } finally {
      _running = false;
    }
  }
}

/// Uploader de M0 : aucune API métier n'existe encore, on reste en attente.
class NotYetAvailableUploader implements OperationUploader {
  const NotYetAvailableUploader();
  @override
  Future<void> upload(PendingOperation op) async =>
      throw const RetryableSyncException('API de synchro disponible en M2');
}
