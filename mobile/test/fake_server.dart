import 'package:dio/dio.dart';

import 'fakes.dart';

/// Serveur simulé du contrat M2 : coupé tant que [online] est faux.
class FakeServer {
  bool online = true;
  final created = <Map<String, dynamic>>[];
  final attachments = <String>[];
  final transitions = <String>[];
  final keys = <String>[];
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
}
