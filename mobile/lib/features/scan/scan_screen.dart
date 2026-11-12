import 'package:material_ui/material_ui.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'package:essor_mobile/core/scan/qr_location.dart';

/// Caméra plein écran : renvoie le premier QR de lieu ESSOR reconnu.
class ScanScreen extends StatefulWidget {
  const ScanScreen({super.key});

  @override
  State<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends State<ScanScreen> {
  bool _done = false;
  String? _rejected;

  void _onDetect(BarcodeCapture capture) {
    if (_done) return;
    for (final b in capture.barcodes) {
      final raw = b.rawValue;
      if (parseQrLocation(raw) != null) {
        _done = true;
        Navigator.of(context).pop(raw);
        return;
      }
      if (raw != null) setState(() => _rejected = raw);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Scanner une zone ou un site')),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: MobileScanner(onDetect: _onDetect)),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              _rejected == null
                  ? 'Visez le QR code affiché dans la zone.'
                  : 'Ce QR code n\'est pas un lieu ESSOR.',
              style: const TextStyle(fontSize: 18),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 18),
            child: OutlinedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Annuler'),
            ),
          ),
        ],
      ),
    );
  }
}
