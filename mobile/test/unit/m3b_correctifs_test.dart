import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:essor_mobile/core/m3b/controls_repository.dart';
import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/sync/api_uploader.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/sync_service.dart';

import '../fake_server.dart';

/// Réponses RÉELLES de l'API, écrites par les tests d'intégration du back.
Map<String, dynamic> example(String name) =>
    jsonDecode(File('../docs/api-exemples/$name.json').readAsStringSync())
        as Map<String, dynamic>;

void main() {
  group('exemples réels de l\'API (docs/api-exemples)', () {
    test('fiche lot {lot, history, inspections}', () {
      final j = example('lot');
      final lot = lotFromDetail(j);
      final inner = j['lot'] as Map<String, dynamic>;
      expect(lot.id, inner['id']);
      expect(lot.numero, inner['numero']);
      expect(lot.status, inner['qualityStatus']);
      expect(lot.matchesNumber(inner['supplierLot'] as String), isTrue);
    });

    test('contrôle : lot et plan embarqués, instrument requis = nom', () {
      final j = example('inspection');
      final lot = Lot.fromJson(j['lot'] as Map<String, dynamic>);
      expect(lot.id, (j['lot'] as Map)['id']);
      final plan = ControlPlan.fromJson(
        j['controlPlan'] as Map<String, dynamic>,
      );
      expect(plan.id, (j['controlPlan'] as Map)['id']);
      final diam = plan.characteristics.firstWhere((c) => c.code == 'DIAM');
      expect(diam.instrumentRequis, 'pied à coulisse');
      expect(diam.limits, (9.0, 11.0));
      expect(
        plan.characteristics
            .firstWhere((c) => c.code == 'ASPECT')
            .instrumentRequis,
        isNull,
      );
    });
  });

  test('tolérances absolues : 16,5 hors tolérance pour un max de 15,5', () {
    const c = Characteristic(
      code: 'EP',
      libelle: 'Épaisseur',
      type: CharType.mesure,
      nominal: 15,
      tolMin: 14.5,
      tolMax: 15.5,
    );
    expect(measureVerdict(c, [16.5]), isFalse);
    expect(measureVerdict(c, [15.5, 14.5]), isTrue);
  });

  test('décision réservée au responsable qualité et à l\'auditeur', () {
    expect(canDecide(['FIELD_USER']), isFalse);
    expect(canDecide(['QUALITY_MANAGER']), isTrue);
    expect(canDecide(['AUDITOR']), isTrue);
    expect(Decision.all, contains(Decision.detruit));
  });

  test('404 : retenté, pas de conflit définitif', () {
    DioException err(int code) => DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: code,
        data: {'message': 'Introuvable'},
      ),
    );
    expect(classify(err(404)), isA<RetryableSyncException>());
    expect(classify(err(400)), isA<ConflictSyncException>());
  });

  test('FIELD_USER : contrôle mis en file sans décision', () async {
    final db = SyncDatabase(NativeDatabase.memory());
    addTearDown(db.close);
    final repo = ControlsRepository(db, OperationQueue(db));
    final server = FakeServer();
    await db.putCache(lotsCacheKey, jsonEncode(server.lots));
    await db.putCache(plansCacheKey, jsonEncode(server.plans));
    final lot = (await repo.cachedLots()).first;
    final plan = (await repo.cachedPlans()).first;
    await repo.record(lot: lot, plan: plan, results: const []);
    final kinds = [for (final o in await db.pending()) o.kind];
    expect(kinds, isNot(contains(OpKind.inspectionDecision)));
    expect(kinds, hasLength(2));
    expect((await repo.cachedLots()).first.status, lot.status);
  });
}
