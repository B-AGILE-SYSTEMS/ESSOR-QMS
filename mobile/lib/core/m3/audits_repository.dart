import 'dart:convert';

import 'package:essor_mobile/core/m3/m3_api.dart';
import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

const auditsCacheKey = 'm3.audits';
String questionsKey(String auditId) => 'm3.audit.$auditId.questions';
String answersKey(String auditId) => 'm3.audit.$auditId.answers';

/// Décode une liste JSON du cache ; liste vide si absente ou illisible.
List<T> decodeList<T>(String? raw, T Function(Map<String, dynamic>) f) {
  if (raw == null) return const [];
  try {
    return List.unmodifiable([
      for (final j in jsonDecode(raw) as List<dynamic>)
        f(j as Map<String, dynamic>),
    ]);
  } on FormatException {
    return const [];
  }
}

/// « Mes audits » : liste et grilles en cache, réponses saisies hors ligne.
class AuditsRepository {
  AuditsRepository(this._db, this._queue);
  final SyncDatabase _db;
  final OperationQueue _queue;

  Stream<List<AuditSummary>> watch() => _db
      .watchCache(auditsCacheKey)
      .map((raw) => decodeList(raw, AuditSummary.fromJson));

  Stream<List<AuditQuestion>> watchQuestions(String auditId) => _db
      .watchCache(questionsKey(auditId))
      .map((raw) => decodeList(raw, AuditQuestion.fromJson));

  Stream<Map<String, AuditAnswer>> watchAnswers(String auditId) =>
      _db.watchCache(answersKey(auditId)).map(_answers);

  Future<Map<String, AuditAnswer>> answers(String auditId) async =>
      _answers(await _db.getCache(answersKey(auditId)));

  /// Recharge les audits à exécuter (PLANIFIE, EN_COURS) et leurs grilles.
  Future<void> refresh(M3Api api, {required int year}) async {
    final list = [
      for (final a in await api.audits(year))
        if (a.executable) a,
    ];
    for (final a in list) {
      final qs = await api.checklist(a.id);
      await _db.putCache(
        questionsKey(a.id),
        jsonEncode([for (final q in qs) q.toJson()]),
      );
    }
    await _db.putCache(
      auditsCacheKey,
      jsonEncode([for (final a in list) a.toJson()]),
    );
  }

  /// Enregistre la réponse localement et la met en file
  /// (`PUT /api/audits/{id}/answers/{questionId}`), photos comprises.
  Future<void> answer({
    required String auditId,
    required String questionId,
    required String result,
    String? comment,
    List<String> photoPaths = const [],
  }) => _db.transaction(() async {
    final current = await answers(auditId);
    final before = current[questionId]?.photos ?? 0;
    final text = (comment ?? '').trim();
    final note = text.isEmpty ? null : text;
    await _queue.auditAnswer(
      auditId: auditId,
      questionId: questionId,
      result: result,
      comment: note,
    );
    for (final p in photoPaths) {
      await _queue.attach(recordId: auditId, path: p);
    }
    final next = {
      ...current,
      questionId: AuditAnswer(
        result: result,
        comment: note,
        photos: before + photoPaths.length,
      ),
    };
    await _db.putCache(
      answersKey(auditId),
      jsonEncode({for (final e in next.entries) e.key: e.value.toJson()}),
    );
  });

  static Map<String, AuditAnswer> _answers(String? raw) {
    if (raw == null) return const {};
    try {
      final m = jsonDecode(raw) as Map<String, dynamic>;
      return Map.unmodifiable({
        for (final e in m.entries)
          e.key: AuditAnswer.fromJson(e.value as Map<String, dynamic>),
      });
    } on FormatException {
      return const {};
    }
  }
}
