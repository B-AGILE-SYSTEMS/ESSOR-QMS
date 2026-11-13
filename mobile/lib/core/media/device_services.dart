import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:path_provider/path_provider.dart';

import 'package:essor_mobile/core/m3b/gs1.dart';
import 'package:essor_mobile/core/m4/instruments.dart';
import 'package:essor_mobile/core/media/image_compress.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/features/scan/scan_screen.dart';

/// Prise de photo (appareil du téléphone) ; `null` si annulée.
typedef PhotoTaker = Future<Uint8List?> Function();

final photoTakerProvider = Provider<PhotoTaker>((ref) {
  return () async {
    final file = await ImagePicker().pickImage(source: ImageSource.camera);
    return file?.readAsBytes();
  };
});

/// Lecture d'un QR code ; renvoie le texte brut ou `null`.
typedef QrScanner = Future<String?> Function(BuildContext context);

final qrScannerProvider = Provider<QrScanner>(
  (ref) =>
      (context) => Navigator.of(context)
          .push<String>(MaterialPageRoute(builder: (_) => const ScanScreen())),
);

/// Lecture d'un code lot (GS1-128 ou QR `essor://lot/<id>`).
final lotScannerProvider = Provider<QrScanner>(
  (ref) =>
      (context) => Navigator.of(context).push<String>(
        MaterialPageRoute(
          builder: (_) => ScanScreen(
            title: 'Scanner un lot',
            hint: 'Visez l\'étiquette GS1-128 ou le QR du lot.',
            rejected: 'Ce code n\'identifie pas un lot.',
            accept: (raw) => parseLotScan(raw) != null,
          ),
        ),
      ),
);

/// Lecture d'un QR instrument (`essor://instrument/<id>`).
final instrumentScannerProvider = Provider<QrScanner>(
  (ref) =>
      (context) => Navigator.of(context).push<String>(
        MaterialPageRoute(
          builder: (_) => ScanScreen(
            title: 'Scanner un instrument',
            hint: 'Visez le QR collé sur l\'instrument.',
            rejected: 'Ce QR code n\'identifie pas un instrument.',
            accept: (raw) => parseInstrumentQr(raw) != null,
          ),
        ),
      ),
);

/// Répertoire des photos en attente d'envoi.
final photoDirProvider = FutureProvider<Directory>((ref) async {
  final base = await getApplicationDocumentsDirectory();
  return Directory('${base.path}/photos').create(recursive: true);
});

/// Compresse (1600 px, JPEG 80 %) et range la photo ; renvoie son chemin.
Future<String?> storePhoto(Directory dir, Uint8List raw) async {
  final jpeg = compressImage(raw);
  if (jpeg == null) return null;
  final file = File('${dir.path}/${newUuid()}.jpg');
  await file.writeAsBytes(jpeg, flush: true);
  return file.path;
}
