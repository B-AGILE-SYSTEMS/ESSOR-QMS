import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m4/instruments.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';
import 'package:essor_mobile/features/instruments/instrument_card.dart';

/// Scan d'un instrument (M4) : fiche, statut, échéance ; hors ligne
/// depuis le cache.
class InstrumentScreen extends ConsumerStatefulWidget {
  const InstrumentScreen({super.key});

  @override
  ConsumerState<InstrumentScreen> createState() => _InstrumentScreenState();
}

class _InstrumentScreenState extends ConsumerState<InstrumentScreen> {
  Instrument? _found;
  String? _message;

  Future<void> _scan() async {
    final raw = await ref.read(instrumentScannerProvider)(context);
    final id = parseInstrumentQr(raw);
    if (id == null) return;
    final api = ref.read(apiClientProvider);
    final found = await ref
        .read(instrumentsRepositoryProvider)
        .find(id, api == null ? null : InstrumentsApi(api.dio));
    if (!mounted) return;
    setState(() {
      _found = found;
      _message = found == null ? 'Instrument inconnu sur le téléphone.' : null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final found = _found;
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: 'Instrument',
              subtitle: 'Scan du QR',
              onBack: () => context.go('/'),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (found != null)
                      InstrumentCard(
                        instrument: found,
                        now: ref.read(clockProvider)(),
                      ),
                    if (_message case final m?)
                      Text(
                        m,
                        key: const Key('instrumentMessage'),
                        style: const TextStyle(fontSize: 17),
                      ),
                  ],
                ),
              ),
            ),
            BottomActions(
              children: [
                FilledButton.icon(
                  key: const Key('scanInstrument'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(minTarget),
                  ),
                  onPressed: _scan,
                  icon: const Icon(Icons.qr_code_scanner, size: 26),
                  label: const Text('Scanner un instrument'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
