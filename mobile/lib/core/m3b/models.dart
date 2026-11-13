/// Modèles du contrat M3b (lots, plans de contrôle) côté mobile.
library;

String? _str(Object? v) => v == null ? null : '$v';
double? _num(Object? v) => switch (v) {
  num n => n.toDouble(),
  String s => double.tryParse(s.replaceAll(',', '.')),
  _ => null,
};

/// Identifiant ou code d'une référence (`"A1"` ou `{id, code, ...}`).
Iterable<String> _refKeys(Object? v) sync* {
  if (v is Map) {
    for (final k in const ['id', 'code']) {
      if (v[k] != null) yield '${v[k]}';
    }
  } else if (v != null) {
    yield '$v';
  }
}

class Lot {
  const Lot({
    required this.id,
    required this.numero,
    this.articleKeys = const [],
    this.articleLabel = '',
    this.famille,
    this.supplierKeys = const [],
    this.supplierLot,
    this.quantity,
    this.unit,
    this.dlc,
    this.status = 'EN_ATTENTE',
    required this.raw,
  });
  final String id;
  final String numero;
  final List<String> articleKeys;
  final String articleLabel;
  final String? famille;
  final List<String> supplierKeys;
  final String? supplierLot;
  final double? quantity;
  final String? unit;
  final String? dlc;
  final String status;
  final Map<String, dynamic> raw;

  factory Lot.fromJson(Map<String, dynamic> j) {
    final article = j['article'];
    final supplier = j['supplier'] ?? j['fournisseur'] ?? j['partner'];
    return Lot(
      id: '${j['id']}',
      numero: _str(j['numero'] ?? j['number']) ?? '',
      articleKeys: List.unmodifiable([
        ..._refKeys(article),
        ..._refKeys(j['articleId']),
      ]),
      articleLabel: article is Map
          ? [
              article['code'],
              article['designation'] ?? article['name'],
            ].whereType<Object>().join(' · ')
          : _str(article) ?? '',
      famille: _str(article is Map ? article['famille'] : j['famille']),
      supplierKeys: List.unmodifiable([
        ..._refKeys(supplier),
        ..._refKeys(j['supplierId'] ?? j['partnerId']),
      ]),
      supplierLot: _str(j['supplierLot'] ?? j['supplierLotNumber']),
      quantity: _num(j['quantity'] ?? j['quantite']),
      unit: _str(j['unit'] ?? j['unite']),
      dlc: _str(j['dlc'] ?? j['expiryDate']),
      status: _str(j['qualityStatus']) ?? 'EN_ATTENTE',
      raw: j,
    );
  }

  Map<String, dynamic> toJson() => raw;

  Lot withStatus(String s) => Lot.fromJson({...raw, 'qualityStatus': s});

  /// Le numéro lu (AI 10) est notre numéro ou celui du fournisseur.
  bool matchesNumber(String n) =>
      numero.toUpperCase() == n.toUpperCase() ||
      supplierLot?.toUpperCase() == n.toUpperCase();
}

enum CharType { mesure, attribut, document }

class Characteristic {
  const Characteristic({
    required this.code,
    required this.libelle,
    required this.type,
    this.nominal,
    this.tolMin,
    this.tolMax,
    this.unite,
    this.instrumentRequis = false,
    this.criticite = 'majeure',
  });
  final String code;
  final String libelle;
  final CharType type;
  final double? nominal;
  final double? tolMin;
  final double? tolMax;
  final String? unite;
  final bool instrumentRequis;
  final String criticite;

  factory Characteristic.fromJson(Map<String, dynamic> j) => Characteristic(
    code: '${j['code']}',
    libelle: _str(j['libelle']) ?? '${j['code']}',
    type: CharType.values.firstWhere(
      (t) => t.name == j['type'],
      orElse: () => CharType.attribut,
    ),
    nominal: _num(j['nominal']),
    tolMin: _num(j['tolMin']),
    tolMax: _num(j['tolMax']),
    unite: _str(j['unite']),
    instrumentRequis: j['instrumentRequis'] == true,
    criticite: _str(j['criticite']) ?? 'majeure',
  );

  /// Limites absolues. Hypothèse : `tolMin`/`tolMax` sont les limites ;
  /// si elles ne peuvent l'être (limite haute sous le nominal), ce sont
  /// des écarts au nominal.
  (double?, double?) get limits {
    final n = nominal;
    final lo = tolMin;
    final hi = tolMax;
    if (n != null && hi != null && hi < n) {
      return (lo == null ? null : n + lo, n + hi);
    }
    return (lo, hi);
  }

  bool inTolerance(double v) {
    final (lo, hi) = limits;
    return (lo == null || v >= lo) && (hi == null || v <= hi);
  }

  String get toleranceLabel {
    final (lo, hi) = limits;
    final u = unite == null ? '' : ' $unite';
    String f(double x) => x.toString().replaceAll('.', ',');
    if (lo != null && hi != null) return 'Tolérance ${f(lo)} – ${f(hi)}$u';
    if (lo != null) return 'Minimum ${f(lo)}$u';
    if (hi != null) return 'Maximum ${f(hi)}$u';
    return '';
  }
}

/// Verdict d'une mesure : toutes les valeurs saisies et dans les limites.
bool? measureVerdict(Characteristic c, List<double?> values) {
  if (values.isEmpty || values.any((v) => v == null)) return null;
  return values.every((v) => c.inTolerance(v!));
}

class ControlPlan {
  const ControlPlan({
    required this.id,
    required this.name,
    required this.point,
    required this.version,
    required this.characteristics,
    this.sampleSize,
    this.targetKeys = const [],
    this.raw = const {},
  });
  final Map<String, dynamic> raw;
  final String id;
  final String name;
  final String point;
  final int version;
  final List<Characteristic> characteristics;

  /// Taille d'échantillon connue hors ligne (mode « fixe »). En ISO 2859
  /// elle est calculée par le serveur à la création du contrôle.
  final int? sampleSize;
  final List<String> targetKeys;

  factory ControlPlan.fromJson(Map<String, dynamic> j) {
    final s = j['sampling'] ?? j['echantillonnage'];
    final target = j['target'] ?? j['cible'];
    final keys = <String>[];
    if (target is Map) {
      for (final e in target.entries) {
        if (e.key == 'type' || e.key == 'kind') continue;
        keys.addAll(_refKeys(e.value));
      }
    }
    final size = s is! Map
        ? null
        : _num(s['sampleSize']) ??
              (s['mode'] == 'fixe' ? _num(s['taille']) : null);
    return ControlPlan(
      id: '${j['id']}',
      name: _str(j['name'] ?? j['libelle'] ?? j['code']) ?? 'Plan',
      point: _str(j['point']) ?? '',
      version: _num(j['version'])?.toInt() ?? 1,
      characteristics: List.unmodifiable([
        for (final c
            in (j['characteristics'] ?? j['caracteristiques'] ?? [])
                as List<dynamic>)
          Characteristic.fromJson(c as Map<String, dynamic>),
      ]),
      sampleSize: size?.toInt(),
      targetKeys: List.unmodifiable(keys),
      raw: j,
    );
  }

  /// Plan applicable au lot : sans cible, ou cible = article, famille
  /// ou fournisseur du lot.
  bool appliesTo(Lot lot) {
    if (targetKeys.isEmpty) return true;
    final mine = {...lot.articleKeys, ?lot.famille, ...lot.supplierKeys};
    return targetKeys.any(mine.contains);
  }
}

/// Résultat saisi pour une caractéristique.
class CharResult {
  const CharResult({
    required this.characteristic,
    required this.values,
    required this.ok,
    this.instrument,
  });
  final String characteristic;
  final List<Object> values;
  final bool ok;
  final String? instrument;

  Map<String, dynamic> toJson() => {
    'characteristic': characteristic,
    'values': values,
    'ok': ok,
    'instrument': ?instrument,
  };
}

abstract final class Decision {
  static const libere = 'LIBERE';
  static const quarantaine = 'QUARANTAINE';
  static const derogation = 'DEROGATION';
  static const refuse = 'REFUSE';
  static const all = [libere, quarantaine, derogation, refuse];
  static const labels = {
    libere: 'Libérer',
    quarantaine: 'Quarantaine',
    derogation: 'Dérogation',
    refuse: 'Refuser',
  };
}

/// Décision proposée : critique non conforme → REFUSE ; autre écart →
/// QUARANTAINE ; tout conforme → LIBERE.
String proposeDecision(List<Characteristic> cs, Map<String, CharResult> r) {
  var gap = false;
  for (final c in cs) {
    if (r[c.code]?.ok == false) {
      if (c.criticite == 'critique') return Decision.refuse;
      gap = true;
    }
  }
  return gap ? Decision.quarantaine : Decision.libere;
}

/// Le serveur exige un motif pour toute décision autre que LIBERE.
bool decisionNeedsReason(String d) => d != Decision.libere;

/// La dérogation exige le rôle QUALITY_MANAGER (contrat M3b).
const qualityManagerRole = 'QUALITY_MANAGER';
