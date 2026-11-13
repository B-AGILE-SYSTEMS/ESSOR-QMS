import 'package:dio/dio.dart';

import 'package:essor_mobile/core/m3/models.dart';

/// Appels de lecture M3. Les écritures passent par la file M2.
class M3Api {
  const M3Api(this._dio);
  final Dio _dio;

  /// `GET /api/audits?year=` (programme annuel).
  Future<List<AuditSummary>> audits(int year) async {
    final r = await _dio.get<List<dynamic>>(
      '/audits',
      queryParameters: {'year': year},
    );
    return [
      for (final j in r.data ?? const [])
        AuditSummary.fromJson(j as Map<String, dynamic>),
    ];
  }

  /// Grille d'un audit. Hypothèse : `GET /api/audits/{id}/checklist`
  /// (la lecture de la grille n'est pas précisée par le contrat M3).
  Future<List<AuditQuestion>> checklist(String auditId) async {
    final r = await _dio.get<List<dynamic>>('/audits/$auditId/checklist');
    return [
      for (final j in r.data ?? const [])
        AuditQuestion.fromJson(j as Map<String, dynamic>),
    ];
  }

  /// `GET /api/documents?status=DIFFUSE` : documents en vigueur.
  Future<List<DocumentItem>> documentsInForce() async {
    final r = await _dio.get<List<dynamic>>(
      '/documents',
      queryParameters: {'status': 'DIFFUSE'},
    );
    return [
      for (final j in r.data ?? const [])
        DocumentItem.fromJson(j as Map<String, dynamic>),
    ];
  }

  /// Fichier de la version. Hypothèse : `GET .../versions/{v}/file`
  /// (le téléchargement n'est pas précisé par le contrat M3).
  Future<List<int>> documentFile(DocumentItem d) async {
    final r = await _dio.get<List<int>>(
      '/documents/${d.id}/versions/${d.version}/file',
      options: Options(responseType: ResponseType.bytes),
    );
    return r.data ?? const [];
  }
}
