import 'dart:typed_data';

import 'package:image/image.dart' as img;

/// Côté le plus long après réduction (contrat M2).
const maxImageSide = 1600;

/// Qualité JPEG à l'envoi (contrat M2).
const jpegQuality = 80;

/// Réduit l'image à [maxImageSide] px au plus et la réencode en JPEG 80 %.
/// Renvoie `null` si les octets ne sont pas une image lisible.
Uint8List? compressImage(Uint8List bytes) {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) return null;
  final oriented = img.bakeOrientation(decoded);
  final longest = oriented.width > oriented.height
      ? oriented.width
      : oriented.height;
  final resized = longest <= maxImageSide
      ? oriented
      : oriented.width >= oriented.height
      ? img.copyResize(oriented, width: maxImageSide)
      : img.copyResize(oriented, height: maxImageSide);
  return img.encodeJpg(resized, quality: jpegQuality);
}
