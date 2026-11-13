import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Réponses saisies d'un audit (cache local).
final auditAnswersProvider =
    StreamProvider.family<Map<String, AuditAnswer>, String>(
      (ref, id) => ref.watch(auditsRepositoryProvider).watchAnswers(id),
    );

/// Grille d'un audit (cache local).
final auditQuestionsProvider =
    StreamProvider.family<List<AuditQuestion>, String>(
      (ref, id) => ref.watch(auditsRepositoryProvider).watchQuestions(id),
    );

/// « Mes audits » : audits planifiés ou en cours, disponibles hors ligne.
class AuditsScreen extends ConsumerWidget {
  const AuditsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final audits = ref.watch(auditsProvider).value ?? const [];
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: 'Mes audits',
              subtitle: '${audits.length} à réaliser',
              onBack: () => context.go('/'),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (audits.isEmpty)
                    const Text(
                      'Aucun audit en cache pour l\'instant.',
                      style: TextStyle(fontSize: 16),
                    ),
                  for (final a in audits) AuditTile(audit: a),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tuile d'audit : titre, nombre de questions et avancement.
class AuditTile extends ConsumerWidget {
  const AuditTile({super.key, required this.audit});
  final AuditSummary audit;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final qs = ref.watch(auditQuestionsProvider(audit.id)).value ?? const [];
    final done = ref.watch(auditAnswersProvider(audit.id)).value?.length ?? 0;
    final scheme = Theme.of(context).colorScheme;
    final sub = [
      '${qs.length} questions',
      if (done > 0) '$done répondues',
      ?audit.siteName,
      if (audit.plannedDate != null) 'prévu le ${audit.plannedDate}',
    ].join(' · ');
    return Container(
      key: Key('audit-${audit.id}'),
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.onSurface, width: 2),
      ),
      child: InkWell(
        onTap: () => context.go('/audit/${audit.id}'),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: minTarget),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  audit.title,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(sub, style: const TextStyle(fontSize: 15)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
