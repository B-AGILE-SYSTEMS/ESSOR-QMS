import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m3b/controls_repository.dart';
import 'package:essor_mobile/core/m3b/gs1.dart';
import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/controls/characteristic_step.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Contrôle guidé d'un lot (M3b) : scan, plan, une caractéristique par
/// écran, décision proposée, photos. Tout part par la file M2.
class ControlScreen extends ConsumerStatefulWidget {
  const ControlScreen({super.key});

  @override
  ConsumerState<ControlScreen> createState() => _ControlScreenState();
}

class _ControlScreenState extends ConsumerState<ControlScreen> {
  final _number = TextEditingController();
  final _comment = TextEditingController();
  Lot? _lot;
  List<ControlPlan> _plans = const [];
  ControlPlan? _plan;
  int _index = 0;
  final _results = <String, CharResult>{};
  final _photos = <String>[];
  String? _decision;
  String? _message;
  bool _saved = false;
  bool _busy = false;

  @override
  void dispose() {
    _number.dispose();
    _comment.dispose();
    super.dispose();
  }

  Future<void> _scan() async {
    final raw = await ref.read(lotScannerProvider)(context);
    if (raw != null) await _lookup(raw);
  }

  Future<void> _lookup(String raw) async {
    final scan = parseLotScan(raw) ?? Gs1Label(lot: raw.trim());
    if (scan is Gs1Label && scan.lot == null && scan.sscc == null) return;
    setState(() => _busy = true);
    final repo = ref.read(controlsRepositoryProvider);
    final api = ref.read(apiClientProvider);
    final lot = await repo.find(scan, api == null ? null : M3bApi(api.dio));
    final plans = await repo.cachedPlans();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (lot == null) {
        _message = 'Lot introuvable sur le téléphone.';
        return;
      }
      _message = null;
      _lot = lot;
      final fitting = plans.where((p) => p.appliesTo(lot)).toList();
      _plans = fitting.isEmpty ? plans : fitting;
    });
  }

  void _choose(ControlPlan p) => setState(() {
    _plan = p;
    _index = 0;
    _results.clear();
  });

  void _onResult(CharResult r) => setState(() {
    _results[r.characteristic] = r;
  });

  void _next(ControlPlan p) => setState(() {
    _index++;
    if (_index >= p.characteristics.length) {
      _decision ??= proposeDecision(p.characteristics, _results);
    }
  });

  Future<void> _takePhoto() async {
    final raw = await ref.read(photoTakerProvider)();
    if (raw == null) return;
    final dir = await ref.read(photoDirProvider.future);
    final path = await storePhoto(dir, raw);
    if (path == null || !mounted) return;
    setState(() => _photos.add(path));
  }

  bool get _canDerogate {
    final s = ref.read(sessionProvider);
    return s is Authenticated && s.me.roles.contains(qualityManagerRole);
  }

  Future<void> _save(Lot lot, ControlPlan plan) async {
    final decision = _decision;
    if (decision == null) return;
    if (decisionNeedsReason(decision) && _comment.text.trim().isEmpty) {
      setState(() => _message = 'Cette décision exige un motif.');
      return;
    }
    await ref
        .read(controlsRepositoryProvider)
        .record(
          lot: lot,
          plan: plan,
          results: [
            for (final c in plan.characteristics)
              if (_results[c.code] != null) _results[c.code]!,
          ],
          decision: decision,
          comment: _comment.text,
          photoPaths: List.of(_photos),
        );
    if (!mounted) return;
    setState(() {
      _saved = true;
      _message = null;
    });
    final online = ref.read(onlineProvider).value ?? false;
    if (online) await runSyncCycleFromWidget(ref);
  }

  @override
  Widget build(BuildContext context) {
    final lot = _lot;
    final plan = _plan;
    final (subtitle, body, actions) = switch ((lot, plan)) {
      _ when _saved => ('Terminé', _done(), <Widget>[]),
      (null, _) => ('Étape 1 · Lot', _scanStep(), <Widget>[]),
      (final Lot l, null) => ('Étape 2 · Plan', _planStep(l), <Widget>[]),
      (final Lot l, final ControlPlan p)
          when _index < p.characteristics.length =>
        (
          'Caractéristique ${_index + 1} sur ${p.characteristics.length}',
          CharacteristicStep(
            key: ValueKey('char-$_index'),
            characteristic: p.characteristics[_index],
            initial: _results[p.characteristics[_index].code],
            sampleSize: p.sampleSize ?? 1,
            onChanged: _onResult,
          ),
          [_nextButton(p)],
        ),
      (final Lot l, final ControlPlan p) => (
        'Décision',
        _decisionStep(l, p),
        [_saveButton(l, p)],
      ),
    };
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: lot == null ? 'Contrôler un lot' : 'Lot ${lot.numero}',
              subtitle: subtitle,
              onBack: () => context.go('/'),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (plan != null && !_saved)
                      StepBar(
                        step: _index + 1,
                        total: plan.characteristics.length + 1,
                      ),
                    const SizedBox(height: 12),
                    body,
                    if (_message != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(
                          _message!,
                          key: const Key('controlMessage'),
                          style: const TextStyle(fontSize: 17),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            if (actions.isNotEmpty) BottomActions(children: actions),
          ],
        ),
      ),
    );
  }

  Widget _scanStep() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      FilledButton.icon(
        key: const Key('scanLot'),
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(tapBigHeightControl),
        ),
        onPressed: _busy ? null : _scan,
        icon: const Icon(Icons.qr_code_scanner, size: 28),
        label: const Text('Scanner l\'étiquette du lot'),
      ),
      const SizedBox(height: 16),
      TextField(
        key: const Key('lotNumber'),
        controller: _number,
        decoration: const InputDecoration(
          labelText: 'Ou saisir le numéro de lot',
          border: OutlineInputBorder(),
        ),
        onSubmitted: _lookup,
      ),
      const SizedBox(height: 10),
      OutlinedButton(
        key: const Key('lotSearch'),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(minTarget),
        ),
        onPressed: _busy ? null : () => _lookup(_number.text),
        child: const Text('Rechercher'),
      ),
    ],
  );

  Widget _lotCard(Lot l) => Text(
    [
      l.articleLabel,
      if (l.quantity != null) '${_fmt(l.quantity!)} ${l.unit ?? ''}'.trim(),
      if (l.dlc != null) 'DLC ${l.dlc}',
      'Statut ${l.status}',
    ].where((s) => s.isNotEmpty).join(' · '),
    key: const Key('lotInfo'),
    style: const TextStyle(fontSize: 16),
  );

  Widget _planStep(Lot l) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _lotCard(l),
      const SizedBox(height: 12),
      if (_plans.isEmpty)
        const Text('Aucun plan de contrôle sur le téléphone.'),
      for (final p in _plans)
        OptionButton(
          key: Key('plan-${p.id}'),
          icon: '▤',
          label: '${p.name} · ${p.point} · v${p.version}',
          selected: false,
          onTap: () => _choose(p),
        ),
    ],
  );

  Widget _nextButton(ControlPlan p) {
    final c = p.characteristics[_index];
    return FilledButton(
      key: const Key('controlNext'),
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(minTarget),
      ),
      onPressed: _results[c.code] == null ? null : () => _next(p),
      child: const Text('Suivante →'),
    );
  }

  Widget _decisionStep(Lot l, ControlPlan p) {
    final proposed = proposeDecision(p.characteristics, _results);
    final nok = _results.values.where((r) => !r.ok).length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _lotCard(l),
        const SizedBox(height: 8),
        Text(
          nok == 0
              ? 'Toutes les caractéristiques sont conformes.'
              : '$nok caractéristique(s) non conforme(s).',
          key: const Key('controlSummary'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        Text(
          'Décision proposée : ${Decision.labels[proposed]}',
          key: const Key('proposedDecision'),
          style: const TextStyle(fontSize: 17),
        ),
        const SizedBox(height: 12),
        for (final d in Decision.all)
          if (d != Decision.derogation || _canDerogate)
            OptionButton(
              key: Key('dec-$d'),
              icon: d == proposed ? '★' : '•',
              label: Decision.labels[d]!,
              selected: _decision == d,
              onTap: () => setState(() => _decision = d),
            ),
        TextField(
          key: const Key('decisionComment'),
          controller: _comment,
          minLines: 2,
          maxLines: 4,
          decoration: InputDecoration(
            labelText: decisionNeedsReason(_decision ?? Decision.libere)
                ? 'Motif (obligatoire)'
                : 'Commentaire',
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        OutlinedButton.icon(
          key: const Key('controlPhoto'),
          style: OutlinedButton.styleFrom(
            minimumSize: const Size.fromHeight(minTarget),
          ),
          onPressed: _takePhoto,
          icon: const Icon(Icons.photo_camera, size: 26),
          label: Text('Photo (${_photos.length})'),
        ),
      ],
    );
  }

  Widget _saveButton(Lot l, ControlPlan p) => FilledButton(
    key: const Key('controlSave'),
    style: FilledButton.styleFrom(
      minimumSize: const Size.fromHeight(minTarget),
    ),
    onPressed: _decision == null ? null : () => _save(l, p),
    child: const Text('Valider la décision ✓'),
  );

  Widget _done() {
    final online = ref.watch(onlineProvider).value ?? false;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          'Contrôle enregistré',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
        ),
        Text(
          online ? 'Envoi en cours' : 'Sera envoyé dès le retour du réseau',
          style: const TextStyle(fontSize: 16),
        ),
        const SizedBox(height: 16),
        FilledButton(
          key: const Key('controlDone'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(minTarget),
          ),
          onPressed: () => context.go('/'),
          child: const Text('Terminé'),
        ),
      ],
    );
  }
}

const tapBigHeightControl = 72.0;

String _fmt(double v) =>
    v == v.roundToDouble() ? v.toInt().toString() : '$v'.replaceAll('.', ',');
