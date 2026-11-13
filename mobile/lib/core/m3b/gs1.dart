/// Code lot lu sur une étiquette : GS1-128 ou QR `essor://lot/<id>`.
sealed class LotScan {
  const LotScan();
}

/// Étiquette GS1-128 : AI 00 (SSCC), 01 (GTIN), 10 (lot), 17 (DLC).
final class Gs1Label extends LotScan {
  const Gs1Label({this.sscc, this.gtin, this.lot, this.expiry});
  final String? sscc;
  final String? gtin;
  final String? lot;
  final DateTime? expiry;
}

/// QR interne ESSOR : identifiant serveur du lot.
final class LotQr extends LotScan {
  const LotQr(this.id);
  final String id;
}

/// Séparateur FNC1 transmis par les lecteurs (GS, 0x1D).
const gs = '\u001d';

final _idPattern = RegExp(r'^[A-Za-z0-9._-]{1,64}$');
final _digits = RegExp(r'^\d+$');

/// Analyse le contenu brut d'un code ; `null` s'il n'identifie aucun lot.
LotScan? parseLotScan(String? raw) {
  if (raw == null) return null;
  final text = raw.trim();
  if (text.isEmpty) return null;
  if (text.toLowerCase().startsWith('essor:')) return _parseQr(text);
  final fields = parseGs1(text);
  if (fields == null) return null;
  final date = fields['17'];
  final expiry = date == null ? null : parseGs1Date(date);
  if (date != null && expiry == null) return null;
  if (fields['10'] == null && fields['00'] == null) return null;
  return Gs1Label(
    sscc: fields['00'],
    gtin: fields['01'],
    lot: fields['10'],
    expiry: expiry,
  );
}

LotScan? _parseQr(String text) {
  final uri = Uri.tryParse(text);
  if (uri == null ||
      uri.scheme.toLowerCase() != 'essor' ||
      uri.host.toLowerCase() != 'lot') {
    return null;
  }
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length != 1) return null;
  final id = Uri.decodeComponent(segments.single);
  return _idPattern.hasMatch(id) ? LotQr(id) : null;
}

/// Longueur de la valeur des AI à longueur fixe ; les autres sont variables
/// et se terminent par FNC1 ou en fin de code.
int? _fixedLength(String ai) {
  if (ai == '00') return 18;
  if (ai == '01' || ai == '02') return 14;
  if (RegExp(r'^1[1-7]$').hasMatch(ai)) return 6;
  if (ai == '20') return 2;
  if (RegExp(r'^3[1-6]\d\d$').hasMatch(ai)) return 6;
  if (RegExp(r'^41[0-7]$').hasMatch(ai)) return 13;
  return null;
}

/// Longueur de l'identifiant d'application qui commence la chaîne.
int? _aiLength(String s) {
  if (s.length < 2) return null;
  final two = s.substring(0, 2);
  if (RegExp(r'^(0[0-2]|1[0-7]|2[0-2]|30|37|9\d)$').hasMatch(two)) return 2;
  if (RegExp(r'^(2[4-5]|4[0-2])$').hasMatch(two)) return 3;
  if (RegExp(r'^(3[1-6]|7\d|8\d)$').hasMatch(two)) return 4;
  return null;
}

const _maxVariable = 30;

/// Découpe une chaîne GS1 (FNC1 = GS, ou notation « (AI)valeur »).
/// `null` si la chaîne n'est pas une suite d'AI valide.
Map<String, String>? parseGs1(String raw) {
  var s = raw;
  if (RegExp(r'^\][A-Za-z]\d').hasMatch(s)) s = s.substring(3);
  if (s.startsWith('(')) return _parseBracketed(s);
  final out = <String, String>{};
  while (s.isNotEmpty) {
    if (s.startsWith(gs)) {
      s = s.substring(1);
      continue;
    }
    final n = _aiLength(s);
    if (n == null || s.length <= n || !_digits.hasMatch(s.substring(0, n))) {
      return null;
    }
    final ai = s.substring(0, n);
    final fixed = _fixedLength(ai);
    final String value;
    if (fixed != null) {
      if (s.length < n + fixed) return null;
      value = s.substring(n, n + fixed);
      s = s.substring(n + fixed);
    } else {
      final end = s.indexOf(gs, n);
      value = end < 0 ? s.substring(n) : s.substring(n, end);
      s = end < 0 ? '' : s.substring(end + 1);
      if (value.isEmpty || value.length > _maxVariable) return null;
    }
    if (!_valid(ai, value)) return null;
    out[ai] = value;
  }
  return out.isEmpty ? null : Map.unmodifiable(out);
}

Map<String, String>? _parseBracketed(String s) {
  final matches = RegExp(r'\((\d{2,4})\)([^(]*)').allMatches(s).toList();
  final consumed = matches.fold<int>(0, (n, m) => n + m.group(0)!.length);
  if (matches.isEmpty || consumed != s.length) return null;
  final out = <String, String>{};
  for (final m in matches) {
    final ai = m.group(1)!;
    final value = m.group(2)!.trim();
    final fixed = _fixedLength(ai);
    if (value.isEmpty || (fixed != null && value.length != fixed)) return null;
    if (!_valid(ai, value)) return null;
    out[ai] = value;
  }
  return Map.unmodifiable(out);
}

bool _valid(String ai, String value) {
  if (ai == '00' || ai == '01' || ai == '02') return gs1CheckDigitOk(value);
  if (_fixedLength(ai) != null) return _digits.hasMatch(value);
  return true;
}

/// Clé de contrôle GS1 (modulo 10, poids 3/1 depuis la droite).
bool gs1CheckDigitOk(String digits) {
  if (digits.length < 2 || !_digits.hasMatch(digits)) return false;
  var sum = 0;
  for (var i = 0; i < digits.length - 1; i++) {
    final d = digits.codeUnitAt(digits.length - 2 - i) - 48;
    sum += i.isEven ? d * 3 : d;
  }
  return (10 - sum % 10) % 10 == digits.codeUnitAt(digits.length - 1) - 48;
}

/// Date GS1 AAMMJJ ; JJ = 00 désigne le dernier jour du mois.
DateTime? parseGs1Date(String v) {
  if (v.length != 6 || !_digits.hasMatch(v)) return null;
  final y = 2000 + int.parse(v.substring(0, 2));
  final m = int.parse(v.substring(2, 4));
  final d = int.parse(v.substring(4, 6));
  if (m < 1 || m > 12) return null;
  final last = DateTime(y, m + 1, 0).day;
  if (d > last) return null;
  return DateTime(y, m, d == 0 ? last : d);
}
