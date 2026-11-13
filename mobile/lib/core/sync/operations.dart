import 'dart:convert';

import 'package:uuid/uuid.dart';

import 'package:essor_mobile/core/sync/sync_database.dart';

/// Types d'opérations de la file.
abstract final class OpKind {
  static const create = 'record.create';
  static const transition = 'record.transition';
  static const attachment = 'attachment.upload';
  static const auditAnswer = 'audit.answer';
  static const documentAck = 'document.ack';
  static const inspectionCreate = 'inspection.create';
  static const inspectionResults = 'inspection.results';
  static const inspectionPhoto = 'inspection.photo';
  static const inspectionDecision = 'inspection.decision';
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

  /// Réponse à une question d'audit (M3).
  Future<void> auditAnswer({
    required String auditId,
    required String questionId,
    required String result,
    String? comment,
  }) => _db.enqueue(
    OpKind.auditAnswer,
    jsonEncode({
      'auditId': auditId,
      'questionId': questionId,
      'result': result,
      'comment': ?comment,
    }),
    idempotencyKey: newUuid(),
  );

  /// Accusé de lecture d'une version de document (M3).
  Future<void> documentAck({
    required String documentId,
    required String version,
  }) => _db.enqueue(
    OpKind.documentAck,
    jsonEncode({'documentId': documentId, 'version': version}),
    idempotencyKey: newUuid(),
  );

  /// Création d'un contrôle de lot (M3b) ; renvoie son `clientId`.
  Future<String> inspectionCreate({
    required String lotId,
    required String controlPlanId,
    required String point,
  }) async {
    final clientId = newUuid();
    await _db.enqueue(
      OpKind.inspectionCreate,
      jsonEncode({
        'clientId': clientId,
        'lotId': lotId,
        'controlPlanId': controlPlanId,
        'point': point,
      }),
      idempotencyKey: newUuid(),
    );
    return clientId;
  }

  Future<void> inspectionResults({
    required String inspectionClientId,
    required List<Map<String, dynamic>> results,
  }) => _db.enqueue(
    OpKind.inspectionResults,
    jsonEncode({'inspectionClientId': inspectionClientId, 'results': results}),
    idempotencyKey: newUuid(),
  );

  Future<void> inspectionPhoto({
    required String inspectionClientId,
    required String path,
  }) => _db.enqueue(
    OpKind.inspectionPhoto,
    jsonEncode({'inspectionClientId': inspectionClientId, 'path': path}),
    idempotencyKey: newUuid(),
  );

  Future<void> inspectionDecision({
    required String inspectionClientId,
    required String decision,
    String? comment,
  }) => _db.enqueue(
    OpKind.inspectionDecision,
    jsonEncode({
      'inspectionClientId': inspectionClientId,
      'decision': decision,
      'comment': ?comment,
    }),
    idempotencyKey: newUuid(),
  );
}
