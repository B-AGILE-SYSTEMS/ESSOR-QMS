import 'package:dio/dio.dart';

import 'fakes.dart';

/// Serveur simulé du contrat M2 : coupé tant que [online] est faux.
class FakeServer {
  bool online = true;
  final created = <Map<String, dynamic>>[];
  final attachments = <String>[];
  final transitions = <String>[];
  final keys = <String>[];

  // --- M3 ---
  final answers = <String, Map<String, dynamic>>{};
  final acks = <String>[];
  final opened = <String>[];
  var downloads = 0;
  String? lastAuditYear;

  /// Nombre d'écritures M3 acceptées avant une coupure réseau (null = ∞).
  int? cutAfterWrites;
  var _m3Writes = 0;

  List<Map<String, Object?>> audits = [
    {
      'id': 'aud-1',
      'reference': 'AUD-2026-031',
      'title': 'Audit interne ISO 45001 — Entrepôt Nord',
      'status': 'PLANIFIE',
      'site': {'id': 's1', 'name': 'Entrepôt Nord'},
      'plannedDate': '2026-11-21',
    },
    {
      'id': 'aud-9',
      'reference': 'AUD-2026-001',
      'title': 'Audit clos',
      'status': 'CLOTURE',
    },
  ];
  List<Map<String, Object?>> checklist = [
    {
      'id': 'q1',
      'text': 'Les dangers du poste sont-ils identifiés ?',
      'requirement': {'code': '6.1.2', 'title': 'Identification des dangers'},
    },
    {
      'id': 'q2',
      'text': 'Les allées de circulation sont-elles dégagées ?',
      'requirementCode': '8.1.2',
      'requirementTitle': 'Maîtrise opérationnelle',
    },
    {
      'id': 'q3',
      'text': 'Les extincteurs sont-ils vérifiés ?',
      'requirementCode': '8.2',
    },
  ];
  List<Map<String, Object?>> documents = [
    {
      'id': 'doc-1',
      'code': 'PR-QHSE-07',
      'title': 'Procédure de consignation',
      'type': 'procedure',
      'currentVersion': {'id': 'v3', 'number': 3, 'fileName': 'PR.pdf'},
    },
  ];

  bool _m3Write() {
    final cut = cutAfterWrites;
    if (cut != null && _m3Writes >= cut) {
      online = false;
      return false;
    }
    _m3Writes++;
    return true;
  }

  List<Map<String, Object?>> tasks = [
    {
      'id': 'act-1',
      'reference': 'ACT-2026-0007',
      'type': 'ACTION',
      'status': 'EN_COURS',
      'statusLabel': 'En cours',
      'title': 'Contrôle compteur outil',
      'dueDate': '2026-11-20',
      'overdue': false,
    },
    {
      'id': 'nc-1',
      'reference': 'NC-2026-0418',
      'type': 'NC',
      'status': 'ANALYSE',
      'statusLabel': 'Analyse',
      'title': 'Écart de cote Ø 12,05 mm',
      'dueDate': '2026-11-15',
      'overdue': true,
    },
  ];

  (int, Object?) respond(RequestOptions o) {
    if (!online) {
      throw DioException.connectionError(
        requestOptions: o,
        reason: 'hors ligne',
      );
    }
    final m3 = _m3(o);
    if (m3 != null) return m3;
    final key = o.headers['Idempotency-Key'];
    if (key is String) keys.add(key);
    final p = o.path;
    if (p.endsWith('/me')) return (200, meJson);
    if (p.endsWith('/tenant/branding/logo')) return (404, null);
    if (p.endsWith('/tenant/branding')) return (200, brandingJson);
    if (p.endsWith('/my-tasks')) return (200, tasks);
    if (p.endsWith('/sync/changes')) {
      return (200, {'records': <Object>[], 'serverTime': '2026-11-12T10:00Z'});
    }
    if (p == '/records' && o.method == 'POST') {
      created.add(Map<String, dynamic>.from(o.data as Map));
      return (201, {'id': 'srv-${created.length}'});
    }
    if (p.endsWith('/attachments')) {
      attachments.add(p);
      return (201, {'id': 'pj-${attachments.length}'});
    }
    if (p.contains('/transitions/')) {
      transitions.add(p);
      return (200, {'id': 'x'});
    }
    return (404, {'message': 'inconnu'});
  }

  (int, Object?)? _m3(RequestOptions o) {
    if (!online) return null;
    final p = o.path;
    final ans = RegExp(r'^/audits/([^/]+)/answers/([^/]+)$').firstMatch(p);
    final ack = RegExp(r'^/documents/([^/]+)/versions/([^/]+)/ack$');
    if (ans != null && o.method == 'PUT') {
      if (!_m3Write()) {
        throw DioException.connectionError(requestOptions: o, reason: 'coupé');
      }
      keys.add(o.headers['Idempotency-Key'] as String);
      answers[ans.group(2)!] = Map<String, dynamic>.from(o.data as Map);
      return (200, {'id': ans.group(2)});
    }
    final a = ack.firstMatch(p);
    if (a != null && o.method == 'POST') {
      if (!_m3Write()) {
        throw DioException.connectionError(requestOptions: o, reason: 'coupé');
      }
      keys.add(o.headers['Idempotency-Key'] as String);
      acks.add('${a.group(1)}/${a.group(2)}');
      return (201, null);
    }
    if (p == '/audits') {
      lastAuditYear = '${o.queryParameters['year']}';
      return (200, audits);
    }
    if (RegExp(r'^/audits/[^/]+/checklist$').hasMatch(p)) {
      return (200, checklist);
    }
    if (p == '/documents') return (200, documents);
    if (p.endsWith('/file')) {
      downloads++;
      return (200, '%PDF-1.4 contenu');
    }
    return null;
  }
}
