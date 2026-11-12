import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/records.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Détail d'une fiche : serveur si joignable, sinon résumé en cache.
final recordDetailProvider = FutureProvider.family<RecordDetail?, String>((
  ref,
  id,
) async {
  final cached = (await ref.read(tasksRepositoryProvider).current())
      .where((t) => t.id == id)
      .firstOrNull;
  final api = ref.read(apiClientProvider);
  if (api != null) {
    try {
      return await api.record(id);
    } on DioException {
      // Hors ligne : on retombe sur le cache.
    }
  }
  return cached == null ? null : RecordDetail(cached);
});

class RecordScreen extends ConsumerWidget {
  const RecordScreen({super.key, required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(recordDetailProvider(id));
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: detail.value?.summary.reference ?? 'Fiche',
              onBack: () => context.go('/taches'),
            ),
            Expanded(
              child: switch (detail) {
                AsyncData(:final value?) => _Body(detail: value),
                AsyncData() => const Center(
                  child: Text(
                    'Fiche indisponible hors ligne.',
                    style: TextStyle(fontSize: 17),
                  ),
                ),
                AsyncError() => const Center(child: Text('Erreur.')),
                _ => const Center(child: CircularProgressIndicator()),
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _Body extends StatelessWidget {
  const _Body({required this.detail});
  final RecordDetail detail;

  @override
  Widget build(BuildContext context) {
    final s = detail.summary;
    Widget line(String label, String? value) => value == null || value.isEmpty
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(value, style: const TextStyle(fontSize: 17)),
              ],
            ),
          );
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          s.title,
          style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 12),
        line('Statut', s.statusLabel),
        line('Gravité', s.severity),
        line('Site', s.siteName),
        line('Lieu', detail.data['lieu'] as String?),
        line('Responsable', s.ownerName),
        line('Échéance', s.dueDate),
        line('Description', detail.description),
        const SizedBox(height: minTarget),
      ],
    );
  }
}
