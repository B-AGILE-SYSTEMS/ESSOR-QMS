import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:open_filex/open_filex.dart';

import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Ouvre une copie locale avec le lecteur du téléphone ; `false` si échec.
typedef FileOpener = Future<bool> Function(String path);

final fileOpenerProvider = Provider<FileOpener>(
  (ref) =>
      (path) async => (await OpenFilex.open(path)).type == ResultType.done,
);

/// Documents en vigueur, lisibles hors ligne, avec accusé de lecture.
class DocumentsScreen extends ConsumerWidget {
  const DocumentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final docs = ref.watch(documentsProvider).value ?? const [];
    final toRead = docs.where((d) => !d.acked).length;
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: 'Documents en vigueur',
              subtitle: '$toRead à lire',
              onBack: () => context.go('/'),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (docs.isEmpty)
                    const Text(
                      'Aucun document en cache pour l\'instant.',
                      style: TextStyle(fontSize: 16),
                    ),
                  for (final d in docs) _DocumentCard(doc: d),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DocumentCard extends ConsumerWidget {
  const _DocumentCard({required this.doc});
  final DocumentItem doc;

  Future<void> _open(BuildContext context, WidgetRef ref) async {
    final path = doc.localPath;
    final ok = path != null && await ref.read(fileOpenerProvider)(path);
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Lecture impossible sur ce téléphone')),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final offline = doc.localPath != null;
    return Container(
      key: Key('doc-${doc.id}'),
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.onSurface, width: 2),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            [doc.code, ?doc.versionLabel].join(' · '),
            style: const TextStyle(fontSize: 14),
          ),
          Text(
            doc.title,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          Text(
            [
              offline ? 'Disponible hors ligne' : 'Non téléchargé',
              doc.acked ? 'Lu ✓' : 'À lire',
            ].join(' · '),
            key: Key('docState-${doc.id}'),
            style: const TextStyle(fontSize: 15),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  key: Key('open-${doc.id}'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(minTarget),
                  ),
                  onPressed: offline ? () => _open(context, ref) : null,
                  icon: const Icon(Icons.menu_book, size: 24),
                  label: const Text('Lire'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  key: Key('ack-${doc.id}'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(minTarget),
                  ),
                  onPressed: doc.acked
                      ? null
                      : () => ref
                            .read(documentsRepositoryProvider)
                            .acknowledge(doc),
                  child: const Text('J\'ai lu'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
