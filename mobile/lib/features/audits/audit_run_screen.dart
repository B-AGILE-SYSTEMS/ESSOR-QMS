import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/audits/audits_screen.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Exécution d'un audit question par question (maquette mobile, écran 3),
/// entièrement hors ligne : chaque réponse part par la file M2.
class AuditRunScreen extends ConsumerStatefulWidget {
  const AuditRunScreen({super.key, required this.id});
  final String id;

  @override
  ConsumerState<AuditRunScreen> createState() => _AuditRunScreenState();
}

class _AuditRunScreenState extends ConsumerState<AuditRunScreen> {
  int _index = 0;
  String? _loadedFor;
  String? _result;
  final _comment = TextEditingController();
  final _photos = <String>[];
  bool _dirty = false;

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  void _load(AuditQuestion q, Map<String, AuditAnswer> answers) {
    if (_loadedFor == q.id) return;
    final a = answers[q.id];
    _loadedFor = q.id;
    _result = a?.result;
    _comment.text = a?.comment ?? '';
    _photos.clear();
    _dirty = false;
  }

  Future<void> _commit(AuditQuestion q) async {
    final result = _result;
    if (!_dirty || result == null) return;
    await ref
        .read(auditsRepositoryProvider)
        .answer(
          auditId: widget.id,
          questionId: q.id,
          result: result,
          comment: _comment.text,
          photoPaths: List.of(_photos),
        );
    _photos.clear();
    _dirty = false;
  }

  Future<void> _go(AuditQuestion q, int next, int total) async {
    await _commit(q);
    if (!mounted) return;
    if (next >= total) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Audit enregistré sur le téléphone')),
      );
      context.go('/audits');
      return;
    }
    setState(() => _index = next.clamp(0, total - 1));
  }

  Future<void> _takePhoto() async {
    final raw = await ref.read(photoTakerProvider)();
    if (raw == null) return;
    final dir = await ref.read(photoDirProvider.future);
    final path = await storePhoto(dir, raw);
    if (path == null || !mounted) return;
    setState(() {
      _photos.add(path);
      _dirty = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final audit = (ref.watch(auditsProvider).value ?? const [])
        .where((a) => a.id == widget.id)
        .firstOrNull;
    final qs = ref.watch(auditQuestionsProvider(widget.id)).value ?? const [];
    final answers =
        ref.watch(auditAnswersProvider(widget.id)).value ?? const {};
    final title = audit?.title ?? 'Audit';
    if (qs.isEmpty) {
      return Scaffold(
        body: SafeArea(
          child: Column(
            children: [
              ScreenHeader(title: title, onBack: () => context.go('/audits')),
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('Grille indisponible hors ligne.'),
              ),
            ],
          ),
        ),
      );
    }
    final i = _index.clamp(0, qs.length - 1);
    final q = qs[i];
    _load(q, answers);
    final saved = answers[q.id]?.photos ?? 0;
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: title,
              subtitle: 'Question ${i + 1} sur ${qs.length}',
              onBack: () async {
                await _commit(q);
                if (context.mounted) context.go('/audits');
              },
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    StepBar(step: i + 1, total: qs.length),
                    const SizedBox(height: 12),
                    if (q.requirementCode != null)
                      Text(
                        [
                          '§${q.requirementCode}',
                          ?q.requirementTitle,
                        ].join(' · '),
                        key: const Key('requirement'),
                        style: const TextStyle(fontSize: 14),
                      ),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        q.text,
                        key: const Key('question'),
                        style: const TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    for (final (code, icon, label) in AuditResult.options)
                      OptionButton(
                        key: Key('ans-$code'),
                        icon: icon,
                        label: label,
                        selected: _result == code,
                        onTap: () => setState(() {
                          _result = code;
                          _dirty = true;
                        }),
                      ),
                    if (AuditResult.isGap(_result))
                      const Padding(
                        padding: EdgeInsets.only(bottom: 10),
                        child: Text(
                          'Écart : ajoutez une photo. Une NC liée sera créée.',
                          key: Key('gapNote'),
                          style: TextStyle(fontSize: 15),
                        ),
                      ),
                    TextField(
                      key: const Key('answerComment'),
                      controller: _comment,
                      minLines: 2,
                      maxLines: 5,
                      onChanged: (_) => _dirty = true,
                      decoration: const InputDecoration(
                        labelText: 'Commentaire',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 10),
                    OutlinedButton.icon(
                      key: const Key('answerPhoto'),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(minTarget),
                      ),
                      onPressed: _takePhoto,
                      icon: const Icon(Icons.photo_camera, size: 26),
                      label: Text('Photo (${saved + _photos.length})'),
                    ),
                  ],
                ),
              ),
            ),
            BottomActions(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        key: const Key('auditPrev'),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(minTarget),
                        ),
                        onPressed: i == 0
                            ? null
                            : () => _go(q, i - 1, qs.length),
                        child: const Text('← Précédente'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton(
                        key: const Key('auditNext'),
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(minTarget),
                        ),
                        onPressed: () => _go(q, i + 1, qs.length),
                        child: Text(
                          i == qs.length - 1 ? 'Terminer ✓' : 'Suivante →',
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
