/// Lieu lu sur un QR code (`essor://site/<code>` ou `essor://zone/<code>`).
sealed class QrLocation {
  const QrLocation(this.code);
  final String code;
}

final class SiteQr extends QrLocation {
  const SiteQr(super.code);
}

final class ZoneQr extends QrLocation {
  const ZoneQr(super.code);
}

final _codePattern = RegExp(r'^[A-Za-z0-9._-]{1,64}$');

/// Analyse le contenu brut d'un QR ; `null` s'il n'est pas un lieu ESSOR.
QrLocation? parseQrLocation(String? raw) {
  if (raw == null) return null;
  final uri = Uri.tryParse(raw.trim());
  if (uri == null || uri.scheme.toLowerCase() != 'essor') return null;
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length != 1) return null;
  final code = Uri.decodeComponent(segments.single);
  if (!_codePattern.hasMatch(code)) return null;
  return switch (uri.host.toLowerCase()) {
    'site' => SiteQr(code),
    'zone' => ZoneQr(code),
    _ => null,
  };
}
