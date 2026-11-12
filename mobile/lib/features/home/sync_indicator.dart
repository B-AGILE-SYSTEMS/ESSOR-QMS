import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/providers.dart';

const _offlineColor = Color(0xFFFFB020);
const _onlineColor = Color(0xFF3DDC6B);

/// Libellé de l'indicateur, conforme à la maquette.
String syncLabel({required bool online, required int pending}) {
  if (!online) {
    return pending == 0 ? 'Hors ligne' : 'Hors ligne · $pending en attente';
  }
  return pending == 0 ? 'Synchronisé' : 'Synchro · $pending en attente';
}

/// Barre d'état toujours visible : réseau et file de synchro.
class SyncStatusBar extends ConsumerWidget {
  const SyncStatusBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Tant que l'état réseau est inconnu, on se considère hors ligne.
    final online = ref.watch(onlineProvider).value ?? false;
    final pending = ref.watch(pendingCountProvider).value ?? 0;
    final label = syncLabel(online: online, pending: pending);
    return Semantics(
      liveRegion: true,
      label: label,
      child: Container(
        key: const Key('syncStatus'),
        color: const Color(0xFF111827),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            Icon(
              online ? Icons.circle : Icons.warning_amber_rounded,
              size: 14,
              color: online ? _onlineColor : _offlineColor,
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                label,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Bandeau d'envois en attente (affiché seulement s'il y en a).
class PendingBanner extends ConsumerWidget {
  const PendingBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pending = ref.watch(pendingCountProvider).value ?? 0;
    if (pending == 0) return const SizedBox.shrink();
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: _offlineColor, width: 2),
      ),
      child: Text(
        '⟳ $pending envoi(s) en attente — synchro auto dès le retour du réseau',
        style: const TextStyle(fontSize: 15),
      ),
    );
  }
}

/// Bandeau des opérations refusées par le serveur (« à revoir »).
class ReviewBanner extends ConsumerWidget {
  const ReviewBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final review = ref.watch(reviewCountProvider).value ?? 0;
    if (review == 0) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return Container(
      key: const Key('reviewBanner'),
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.error, width: 2),
      ),
      child: Text(
        '✕ $review envoi(s) à revoir — voir « État des envois »',
        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
      ),
    );
  }
}
