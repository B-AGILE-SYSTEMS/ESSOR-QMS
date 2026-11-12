import 'dart:convert';

import 'package:uuid/uuid.dart';

import 'package:essor_mobile/core/sync/sync_database.dart';

/// Types d'opérations de la file.
abstract final class OpKind {
  static const create = 'record.create';
  static const transition = 'record.transition';
  static const attachment = 'attachment.upload';
}

const _uuid = Uuid();
String newUuid() => _uuid.v4();

/// Écriture des opérations dans la file, avec une clé d'idempotence chacune.
class OperationQueue {
  OperationQueue(this._db);
  final SyncDatabase _db;

  /// Met en file une création de fiche ; renvoie son `clientId`.
  Future<String> createRecord({
    required String type,
    required String siteId,
    required String title,
    String? description,
    String? severity,
    String? category,
    Map<String, Object?> data = const {},
    required DateTime occurredAt,
    List<String> photoPaths = const [],
  }) async {
    final clientId = newUuid();
    await _db.transaction(() async {
      await _db.enqueue(
        OpKind.create,
        jsonEncode({
          'clientId': clientId,
          'type': type,
          'siteId': siteId,
          'title': title,
          'description': ?description,
          'severity': ?severity,
          // Catégorie d'incident : champ du type, stocké dans `data`.
          'data': {...data, 'categorie': ?category},
          'occurredAt': occurredAt.toUtc().toIso8601String(),
        }),
        idempotencyKey: newUuid(),
      );
      for (final p in photoPaths) {
        await attach(recordClientId: clientId, path: p);
      }
    });
    return clientId;
  }

  /// Transition sur une fiche serveur (`recordId`) ou locale (`recordClientId`).
  Future<void> transition({
    String? recordId,
    String? recordClientId,
    required String code,
    String? comment,
  }) => _db.enqueue(
    OpKind.transition,
    jsonEncode({
      'recordId': ?recordId,
      'recordClientId': ?recordClientId,
      'code': code,
      'comment': ?comment,
    }),
    idempotencyKey: newUuid(),
  );

  Future<void> attach({
    String? recordId,
    String? recordClientId,
    required String path,
  }) => _db.enqueue(
    OpKind.attachment,
    jsonEncode({
      'recordId': ?recordId,
      'recordClientId': ?recordClientId,
      'path': path,
    }),
    idempotencyKey: newUuid(),
  );
}
