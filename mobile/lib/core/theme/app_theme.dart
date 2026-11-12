import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/models.dart';

const defaultPrimary = Color(0xFFC2410C);

/// Hauteur minimale des boutons terrain (maquette : ≥ 64 px).
const tapMinHeight = 64.0;
const tapBigHeight = 84.0;

/// `#RRGGBB` → Color ; `null` si invalide.
Color? parseHexColor(String? hex) {
  if (hex == null) return null;
  final m = RegExp(r'^#?([0-9a-fA-F]{6})$').firstMatch(hex.trim());
  if (m == null) return null;
  return Color(0xFF000000 | int.parse(m.group(1)!, radix: 16));
}

/// Thème appliqué selon l'apparence de l'exploitant.
ThemeData buildTheme(Branding b) {
  final primary = parseHexColor(b.primaryColor) ?? defaultPrimary;
  final dark = b.theme == AppTheme.sombre;
  final terrain = b.theme == AppTheme.terrain;
  final scheme = ColorScheme.fromSeed(
    seedColor: primary,
    brightness: dark ? Brightness.dark : Brightness.light,
    // Thème terrain : contraste maximal (haute lisibilité).
    contrastLevel: terrain ? 1.0 : 0.0,
  ).copyWith(primary: primary, onPrimary: onColor(primary));

  final base = ThemeData(colorScheme: scheme, useMaterial3: true);
  const label = TextStyle(fontSize: 18, fontWeight: FontWeight.w700);
  return base.copyWith(
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(tapMinHeight),
        shape: const RoundedRectangleBorder(),
        textStyle: label,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size.fromHeight(tapMinHeight),
        shape: const RoundedRectangleBorder(),
        side: BorderSide(color: scheme.onSurface, width: 2),
        foregroundColor: scheme.onSurface,
        textStyle: label,
      ),
    ),
  );
}

/// Texte noir ou blanc lisible sur la couleur donnée.
Color onColor(Color c) =>
    c.computeLuminance() > 0.45 ? Colors.black : Colors.white;
