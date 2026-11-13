import 'dart:convert';

import 'package:dio/dio.dart';

import 'package:essor_mobile/core/m3/audits_repository.dart' show decodeList;
import 'package:essor_mobile/core/m3b/gs1.dart';
import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

const lotsCacheKey = 'm3b.lots';
const plansCacheKey = 'm3b.plans';

/// Lectures M3b. Les écritures passent par la file M2.
class M3bApi {
  const M3bApi(this._dio);
  final Dio _dio;

  Future<List<Lot>> lots({String? q, String? status}) async {
    final r = await _dio.get<List<dynamic>>(
      '/lots',
      queryParameters: {'q': ?q, 'status': ?status},
    );
    return [
      for (final j in r.data ?? const [])
        Lot.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<Lot> lot(String id) async {
    final r = await _dio.get<Map<String, dynamic>>('/lots/$id');
    return Lot.fromJson(r.data ?? {'id': id});
  }

  Future<List<ControlPlan>> plans() async {
    final r = await _dio.get<List<dynamic>>('/control-plans');
    return [
      for (final j in r.data ?? const [])
        ControlPlan.fromJson(j as Map<String, dynamic>),
    ];
  }
}

/// Lots à contrôler et plans en cache ; contrôles saisis hors ligne.
class ControlsRepository {
  ControlsRepository(this._db, this._queue);
  final SyncDatabase _db;
  final OperationQueue _queue;

  Future<List<Lot>> cachedLots() async =>
      decodeList(await _db.getCache(lotsCacheKey), Lot.fromJson);

  Future<List<ControlPlan>> cachedPlans() async =>
      decodeList(await _db.getCache(plansCacheKey), ControlPlan.fromJson);

  /// Lots en attente de contrôle et plans, à chaque cycle de synchro.
  Future<void> refresh(M3bApi api) async {
    final plans = await api.plans();
    final lots = await api.lots(status: 'EN_ATTENTE');
    await _db.putCache(
      plansCacheKey,
      jsonEncode([for (final p in plans) p.raw]),
    );
    await _putLots(lots);
  }

  Future<void> _putLots(List<Lot> lots) =>
      _db.putCache(lotsCacheKey, jsonEncode([for (final l in lots) l.raw]));

  /// Retrouve le lot scanné : cache d'abord, serveur ensuite si joignable.
  Future<Lot?> find(LotScan scan, M3bApi? api) async {
    final cached = await cachedLots();
    final hit = switch (scan) {
      LotQr(:final id) => cached.where((l) => l.id == id).firstOrNull,
      Gs1Label(:final lot) =>
        lot == null
            ? null
            : cached.where((l) => l.matchesNumber(lot)).firstOrNull,
    };
    if (hit != null || api == null) return hit;
    try {
      final found = switch (scan) {
        LotQr(:final id) => await api.lot(id),
        Gs1Label(:final lot, :final sscc) => (await api.lots(
          q: lot ?? sscc,
        )).firstOrNull,
      };
      if (found != null) {
        await _putLots([
          for (final l in cached)
            if (l.id != found.id) l,
          found,
        ]);
      }
      return found;
    } on DioException {
      return null;
    }
  }

  /// Met en file le contrôle complet : création, résultats, photos, décision.
  /// Le statut du lot est mis à jour localement tout de suite.
  Future<void> record({
    required Lot lot,
    required ControlPlan plan,
    required List<CharResult> results,
    required String decision,
    String? comment,
    List<String> photoPaths = const [],
  }) => _db.transaction(() async {
    final clientId = await _queue.inspectionCreate(
      lotId: lot.id,
      controlPlanId: plan.id,
      point: plan.point,
    );
    await _queue.inspectionResults(
      inspectionClientId: clientId,
      results: [for (final r in results) r.toJson()],
    );
    for (final p in photoPaths) {
      await _queue.inspectionPhoto(inspectionClientId: clientId, path: p);
    }
    final note = (comment ?? '').trim();
    await _queue.inspectionDecision(
      inspectionClientId: clientId,
      decision: decision,
      comment: note.isEmpty ? null : note,
    );
    final lots = await cachedLots();
    await _putLots([
      for (final l in lots) l.id == lot.id ? l.withStatus(decision) : l,
    ]);
  });
}
