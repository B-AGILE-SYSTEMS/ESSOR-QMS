import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/records.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';
import 'package:essor_mobile/core/sync/tasks_repository.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// « Mes tâches » : cache local, action faite marquée hors ligne.
class TasksScreen extends ConsumerWidget {
  const TasksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tasks = ref.watch(tasksProvider).value ?? const [];
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: 'Mes tâches',
              subtitle: '${tasks.length} à suivre',
              onBack: () => context.go('/'),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  const PendingBanner(),
                  if (tasks.isEmpty)
                    const Text(
                      'Aucune tâche en cache. Elles se chargent dès que le '
                      'réseau est disponible.',
                      style: TextStyle(fontSize: 17),
                    ),
                  for (final t in tasks) TaskCard(task: t),
                ],
              ),
            ),
            BottomActions(
              children: [
                OutlinedButton.icon(
                  key: const Key('openOperations'),
                  onPressed: () => context.go('/envois'),
                  icon: const Icon(Icons.sync, size: 26),
                  label: const Text('État des envois'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class TaskCard extends ConsumerWidget {
  const TaskCard({super.key, required this.task});
  final RecordSummary task;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final due = task.dueDate == null ? '' : 'Échéance ${_fr(task.dueDate!)}';
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(
          color: task.overdue ? scheme.error : scheme.onSurface,
          width: 2,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: () => context.go('/fiche/${task.id}'),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    task.title,
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  Text(
                    [
                      task.reference,
                      task.statusLabel,
                      if (due.isNotEmpty) due,
                      if (task.overdue) 'EN RETARD',
                    ].join(' · '),
                    style: const TextStyle(fontSize: 15),
                  ),
                ],
              ),
            ),
          ),
          if (canMarkDone(task))
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
              child: FilledButton.icon(
                key: Key('done-${task.id}'),
                onPressed: () async {
                  await ref.read(tasksRepositoryProvider).markDone(task);
                  unawaited(ref.read(syncServiceProvider).syncNow());
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context)
                    ..hideCurrentSnackBar()
                    ..showSnackBar(
                      const SnackBar(content: Text('Action marquée faite')),
                    );
                },
                icon: const Icon(Icons.check, size: 26),
                label: const Text('Marquer faite'),
              ),
            ),
        ],
      ),
    );
  }
}

String _fr(String iso) {
  final d = DateTime.tryParse(iso);
  if (d == null) return iso;
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(d.day)}/${two(d.month)}';
}

/// File d'opérations : en attente / envoyé / à revoir.
class OperationsScreen extends ConsumerWidget {
  const OperationsScreen({super.key});

  static const _labels = {
    OpState.pending: 'En attente',
    OpState.sent: 'Envoyé',
    OpState.review: 'À revoir',
  };

  static const _kinds = {
    'record.create': 'Nouvelle fiche',
    'record.transition': 'Changement de statut',
    'attachment.upload': 'Photo',
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ops =
        ref.watch(operationsProvider).value ?? const <PendingOperation>[];
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: 'État des envois',
              onBack: () => context.go('/taches'),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  for (final op in ops)
                    ListTile(
                      key: Key('op-${op.id}'),
                      minTileHeight: minTarget,
                      title: Text(
                        '${_kinds[op.kind] ?? op.kind} — '
                        '${_labels[op.state] ?? op.state}',
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      subtitle: op.lastError == null
                          ? null
                          : Text(
                              op.lastError!,
                              style: const TextStyle(fontSize: 15),
                            ),
                    ),
                ],
              ),
            ),
            BottomActions(
              children: [
                FilledButton.icon(
                  key: const Key('syncNow'),
                  onPressed: () => runSyncCycleFromWidget(ref),
                  icon: const Icon(Icons.sync, size: 26),
                  label: const Text('Synchroniser'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
