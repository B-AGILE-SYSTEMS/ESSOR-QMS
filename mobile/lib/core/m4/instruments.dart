/// Métrologie M4 côté mobile : instruments, QR `essor://instrument/<id>`,
/// cache hors ligne et règle « en service et non échu ».
library;

import 'dart:convert';

import 'package:dio/dio.dart';

import 'package:essor_mobile/core/m3/audits_repository.dart' show decodeList;
import 'package:essor_mobile/core/sync/sync_database.dart';

const instrumentsCacheKey = 'm4.instruments';
const selectableInstrumentsCacheKey = 'm4.instruments.selectable';

String? _str(Object? v) => v == null || '$v'.isEmpty ? null : '$v';

/// Date `AAAA-MM-JJ` (ou horodatage ISO) ramenée au jour ; `null` sinon.
DateTime? _day(Object? v) {
  final d = v == null ? null : DateTime.tryParse('$v');
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

DateTime _today(DateTime now) => DateTime(now.year, now.month, now.day);

abstract final class InstrumentStatus {
  static const enService = 'en_service';
  static const bloque = 'bloque';
  static const enEtalonnage = 'en_etalonnage';
  static const reforme = 'reforme';
  static const labels = {
    enService: 'En service',
    bloque: 'Bloqué',
    enEtalonnage: 'En étalonnage',
    reforme: 'Réformé',
  };
}

class Instrument {
  const Instrument({
    required this.id,
    required this.code,
    this.designation = '',
    this.type,
    this.site,
    this.location,
    this.status = InstrumentStatus.enService,
    this.nextDue,
    this.emt,
    required this.raw,
  });
  final String id;
  final String code;
  final String designation;
  final String? type;
  final String? site;
  final String? location;
  final String status;

  /// Prochaine échéance d'étalonnage (jour), ou null si inconnue.
  final DateTime? nextDue;
  final String? emt;
  final Map<String, dynamic> raw;

  /// Accepte les variantes de nommage (anglais du back, français du contrat).
  factory Instrument.fromJson(Map<String, dynamic> j) {
    final site = j['site'];
    return Instrument(
      id: '${j['id']}',
      code: _str(j['code']) ?? '${j['id']}',
      designation: _str(j['designation'] ?? j['label'] ?? j['name']) ?? '',
      type: _str(j['type']),
      site: site is Map ? _str(site['name'] ?? site['code']) : _str(site),
      location: _str(j['location'] ?? j['emplacement']),
      status: (_str(j['status'] ?? j['statut']) ?? InstrumentStatus.enService)
          .toLowerCase(),
      nextDue: _day(
        j['nextCalibrationDate'] ??
            j['nextDueDate'] ??
            j['nextDue'] ??
            j['prochaineEcheance'] ??
            j['dueDate'],
      ),
      emt: _str(j['emt'] ?? j['mpe']),
      raw: j,
    );
  }

  String get statusLabel => InstrumentStatus.labels[status] ?? status;

  bool isOverdue(DateTime now) => nextDue?.isBefore(_today(now)) ?? false;

  /// Motif de refus, ou null si l'instrument peut servir à une mesure.
  /// Réévalué à chaque usage : un instrument en cache peut devenir échu
  /// hors ligne.
  String? refusal(DateTime now) {
    if (status != InstrumentStatus.enService) {
      return 'Instrument ${statusLabel.toLowerCase()} : '
          'il ne peut pas être utilisé.';
    }
    if (isOverdue(now)) return 'Étalonnage échu : instrument inutilisable.';
    return null;
  }

  bool usable(DateTime now) => refusal(now) == null;

  /// Jours restants avant l'échéance (négatif si échu).
  int? daysLeft(DateTime now) => nextDue?.difference(_today(now)).inDays;
}

final _idPattern = RegExp(r'^[A-Za-z0-9._-]{1,64}$');

/// Identifiant lu sur un QR `essor://instrument/<id>` ; `null` sinon.
String? parseInstrumentQr(String? raw) {
  if (raw == null) return null;
  final uri = Uri.tryParse(raw.trim());
  if (uri == null ||
      uri.scheme.toLowerCase() != 'essor' ||
      uri.host.toLowerCase() != 'instrument') {
    return null;
  }
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length != 1) return null;
  final id = Uri.decodeComponent(segments.single);
  return _idPattern.hasMatch(id) ? id : null;
}

/// Liste brute ou page `{content: [...]}`.
List<dynamic> _items(Object? data) => switch (data) {
  final List<dynamic> l => l,
  {'content': final List<dynamic> l} => l,
  {'items': final List<dynamic> l} => l,
  _ => const [],
};

class InstrumentsApi {
  const InstrumentsApi(this._dio);
  final Dio _dio;

  /// Parc complet, ou seulement les instruments choisissables
  /// (`selectable=true` : en service et non échus selon le serveur).
  Future<List<Instrument>> list({bool selectable = false}) async {
    final r = await _dio.get<Object>(
      '/instruments',
      queryParameters: selectable ? const {'selectable': 'true'} : null,
    );
    return [
      for (final j in _items(r.data))
        Instrument.fromJson(j as Map<String, dynamic>),
    ];
  }

  /// `GET /instruments/{id}` : instrument seul ou `{instrument, ...}`.
  Future<Instrument?> get(String id) async {
    final r = await _dio.get<Map<String, dynamic>>('/instruments/$id');
    final j = r.data;
    if (j == null) return null;
    final inner = j['instrument'];
    return Instrument.fromJson(inner is Map<String, dynamic> ? inner : j);
  }
}

/// Parc d'instruments en cache : sert hors ligne au scan et au choix.
class InstrumentsRepository {
  InstrumentsRepository(this._db);
  final SyncDatabase _db;

  Future<List<Instrument>> cached() async =>
      decodeList(await _db.getCache(instrumentsCacheKey), Instrument.fromJson);

  Future<void> _put(List<Instrument> all) => _db.putCache(
    instrumentsCacheKey,
    jsonEncode([for (final i in all) i.raw]),
  );

  /// Parc complet (scan, fiche) et liste de choix du serveur
  /// (`GET /instruments?selectable=true`).
  Future<void> refresh(InstrumentsApi api) async {
    await _put(await api.list());
    final choice = await api.list(selectable: true);
    await _db.putCache(
      selectableInstrumentsCacheKey,
      jsonEncode([for (final i in choice) i.raw]),
    );
  }

  /// Instruments choisissables dans un contrôle, triés par code : liste
  /// `selectable` du serveur (parc complet à défaut), échéance et statut
  /// revérifiés hors ligne.
  Future<List<Instrument>> usable(DateTime now) async {
    final raw = await _db.getCache(selectableInstrumentsCacheKey);
    final source = raw == null
        ? await cached()
        : decodeList(raw, Instrument.fromJson);
    final fresh = {for (final i in await cached()) i.id: i};
    return [
      for (final i in source)
        if ((fresh[i.id] ?? i).usable(now)) fresh[i.id] ?? i,
    ]..sort((a, b) => a.code.compareTo(b.code));
  }

  /// Retrouve un instrument par id ou code : serveur d'abord s'il répond
  /// (statut frais), cache sinon.
  Future<Instrument?> find(String key, InstrumentsApi? api) async {
    final all = await cached();
    final hit = all
        .where((i) => i.id == key || i.code.toUpperCase() == key.toUpperCase())
        .firstOrNull;
    if (api == null) return hit;
    try {
      final fresh = await api.get(hit?.id ?? key);
      if (fresh == null) return hit;
      await _put([
        for (final i in all)
          if (i.id != fresh.id) i,
        fresh,
      ]);
      return fresh;
    } on DioException {
      return hit;
    }
  }
}
