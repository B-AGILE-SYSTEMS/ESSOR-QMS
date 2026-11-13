import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';

import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';

/// Envoie les opérations de la file à l'API M2 (en-tête `Idempotency-Key`).
class ApiOperationUploader implements OperationUploader {
  ApiOperationUploader(this._dio, this._db);
  final Dio _dio;
  final SyncDatabase _db;

  @override
  Future<void> upload(PendingOperation op) async {
    final p = jsonDecode(op.payload) as Map<String, dynamic>;
    final key = op.idempotencyKey.isEmpty ? newUuid() : op.idempotencyKey;
    final options = Options(headers: {'Idempotency-Key': key});
    try {
      switch (op.kind) {
        case OpKind.create:
          final r = await _dio.post<Map<String, dynamic>>(
            '/records',
            data: clampOccurredAt(p, DateTime.now()),
            options: options,
          );
          await _db.saveMapping(p['clientId'] as String, '${r.data?['id']}');
        case OpKind.transition:
          final id = await _recordId(p);
          await _dio.post<Object?>(
            '/records/$id/transitions/${p['code']}',
            data: {'comment': p['comment']},
            options: options,
          );
        case OpKind.attachment:
          final id = await _recordId(p);
          await _sendPhoto('/records/$id/attachments', p, options);
        case OpKind.auditAnswer:
          await _dio.put<Object?>(
            '/audits/${p['auditId']}/answers/${p['questionId']}',
            data: {
              'result': p['result'],
              'comment': p['comment'],
              'evidence': null,
            },
            options: options,
          );
        case OpKind.documentAck:
          await _dio.post<Object?>(
            '/documents/${p['documentId']}/versions/${p['version']}/ack',
            options: options,
          );
        case OpKind.inspectionCreate:
          final r = await _dio.post<Map<String, dynamic>>(
            '/inspections',
            data: {
              'lotId': p['lotId'],
              'controlPlanId': p['controlPlanId'],
              'point': p['point'],
            },
            options: options,
          );
          await _db.saveMapping(p['clientId'] as String, '${r.data?['id']}');
        case OpKind.inspectionResults:
          final id = await _inspectionId(p);
          await _dio.put<Object?>(
            '/inspections/$id/results',
            data: p['results'],
            options: options,
          );
        case OpKind.inspectionPhoto:
          final id = await _inspectionId(p);
          await _sendPhoto('/inspections/$id/attachments', p, options);
        case OpKind.inspectionDecision:
          final id = await _inspectionId(p);
          await _dio.post<Object?>(
            '/inspections/$id/decision',
            data: {'decision': p['decision'], 'comment': p['comment']},
            options: options,
          );
        default:
          throw ConflictSyncException('Opération inconnue : ${op.kind}');
      }
    } on DioException catch (e) {
      throw classify(e);
    }
  }

  Future<void> _sendPhoto(
    String url,
    Map<String, dynamic> p,
    Options options,
  ) async {
    final path = p['path'] as String;
    if (!File(path).existsSync()) {
      throw const ConflictSyncException('Photo introuvable sur le téléphone.');
    }
    await _dio.post<Object?>(
      url,
      data: FormData.fromMap({
        'file': await MultipartFile.fromFile(
          path,
          filename: path.split(Platform.pathSeparator).last,
          contentType: DioMediaType('image', 'jpeg'),
        ),
      }),
      options: options,
    );
  }

  Future<String> _inspectionId(Map<String, dynamic> p) async {
    final id = await _db.serverIdFor(p['inspectionClientId'] as String);
    if (id == null) {
      throw const ConflictSyncException(
        'Le contrôle lié n\'a pas été créé sur le serveur.',
      );
    }
    return id;
  }

  Future<String> _recordId(Map<String, dynamic> p) async {
    final direct = p['recordId'] as String?;
    if (direct != null) return direct;
    final mapped = await _db.serverIdFor(p['recordClientId'] as String);
    if (mapped == null) {
      throw const ConflictSyncException(
        'La fiche liée n\'a pas été créée sur le serveur.',
      );
    }
    return mapped;
  }
}

/// Le serveur refuse une date terrain à plus de 5 min dans le futur :
/// une date postérieure à l'heure du téléphone est ramenée à « maintenant ».
Map<String, dynamic> clampOccurredAt(Map<String, dynamic> p, DateTime now) {
  final at = DateTime.tryParse('${p['occurredAt']}');
  if (at == null || !at.isAfter(now)) return p;
  return {...p, 'occurredAt': now.toUtc().toIso8601String()};
}

/// 4xx (hors 401/408/429) : refus définitif → « à revoir ». Le reste : retenter.
Exception classify(DioException e) {
  final status = e.response?.statusCode;
  if (status != null &&
      status >= 400 &&
      status < 500 &&
      status != 401 &&
      status != 404 && // ressource pas encore visible : on retente
      status != 408 &&
      status != 429) {
    final body = e.response?.data;
    if (body is Map && body['error'] == 'idempotency_in_progress') {
      return const RetryableSyncException('Envoi déjà en cours côté serveur');
    }
    final message = body is Map && body['message'] is String
        ? body['message'] as String
        : 'Refusé par le serveur ($status).';
    return ConflictSyncException(message);
  }
  return RetryableSyncException(
    status == null ? 'Serveur injoignable' : 'Erreur serveur ($status)',
  );
}
