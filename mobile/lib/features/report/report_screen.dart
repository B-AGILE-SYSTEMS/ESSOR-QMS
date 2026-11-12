import 'dart:async';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/scan/qr_location.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';
import 'package:essor_mobile/features/report/report_types.dart';

/// « Signaler » en 3 étapes : type, lieu, description + photos.
class ReportScreen extends ConsumerStatefulWidget {
  const ReportScreen({super.key, this.initialQr});

  /// Contenu d'un QR lu depuis l'accueil, pour préremplir le lieu.
  final String? initialQr;

  @override
  ConsumerState<ReportScreen> createState() => _ReportScreenState();
}

class _ReportScreenState extends ConsumerState<ReportScreen> {
  static const _steps = 3;
  int _step = 1;
  ReportKind? _kind;
  NamedRef? _site;
  String? _zone;
  Danger? _danger;
  final _description = TextEditingController();
  final _photos = <String>[];
  bool _saving = false;
  bool _saved = false;
  String? _message;

  List<NamedRef> get _sites {
    final s = ref.read(sessionProvider);
    return s is Authenticated ? s.me.sites : const [];
  }

  @override
  void initState() {
    super.initState();
    final sites = _sites;
    if (sites.length == 1) _site = sites.first;
    _applyQr(widget.initialQr);
  }

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  /// Applique un QR de lieu ; renvoie `false` s'il n'est pas reconnu.
  bool _applyQr(String? raw) {
    final loc = parseQrLocation(raw);
    switch (loc) {
      case null:
        return false;
      case SiteQr(:final code):
        final match = _sites.where((s) => s.code == code).firstOrNull;
        if (match == null) {
          _message = 'Site $code inconnu pour votre compte.';
          return false;
        }
        _site = match;
        _zone = null;
      case ZoneQr(:final code):
        _zone = code;
        _site ??= _sites.firstOrNull;
    }
    _message = null;
    return true;
  }

  Future<void> _scan() async {
    final raw = await ref.read(qrScannerProvider)(context);
    if (!mounted || raw == null) return;
    setState(() {
      if (!_applyQr(raw)) _message ??= 'QR code non reconnu.';
    });
  }

  Future<void> _takePhoto() async {
    final raw = await ref.read(photoTakerProvider)();
    if (raw == null) return;
    final dir = await ref.read(photoDirProvider.future);
    final path = await storePhoto(dir, raw);
    if (!mounted) return;
    setState(() {
      if (path == null) {
        _message = 'Photo illisible.';
      } else {
        _photos.add(path);
      }
    });
  }

  bool get _canContinue => switch (_step) {
    1 => _kind != null,
    2 => _site != null,
    _ => _danger != null && !_saving,
  };

  String get _lieu {
    final site = _site?.name ?? '';
    return _zone == null ? site : '$site · zone $_zone';
  }

  Future<void> _submit() async {
    final kind = _kind;
    final site = _site;
    final danger = _danger;
    if (kind == null || site == null || danger == null) return;
    setState(() => _saving = true);
    final text = _description.text.trim();
    await ref
        .read(operationQueueProvider)
        .createRecord(
          type: kind.type,
          siteId: site.id,
          title: '${kind.label} — $_lieu',
          description: text.isEmpty ? null : text,
          severity: danger.severity,
          category: kind.category,
          data: {
            'lieu': _lieu,
            if (kind.type == 'INCIDENT')
              'blesses': kind.category == 'accident_travail',
          },
          occurredAt: DateTime.now(),
          photoPaths: List.of(_photos),
        );
    unawaited(ref.read(syncServiceProvider).syncNow());
    if (mounted) setState(() => _saved = true);
  }

  void _next() {
    if (_step < _steps) {
      setState(() => _step++);
    } else {
      unawaited(_submit());
    }
  }

  void _back() {
    if (_step > 1) {
      setState(() => _step--);
    } else {
      context.go('/');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_saved) return _Confirmation(online: ref.watch(onlineProvider).value);
    final titles = {
      1: 'Quel type de problème ?',
      2: 'Où êtes-vous ?',
      3: 'Que se passe-t-il ?',
    };
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            ScreenHeader(
              title: titles[_step]!,
              subtitle: 'Étape $_step sur $_steps',
              onBack: _back,
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  StepBar(step: _step, total: _steps),
                  const SizedBox(height: 14),
                  if (_message != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Text(
                        _message!,
                        key: const Key('reportMessage'),
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  ...switch (_step) {
                    1 => _typeStep(),
                    2 => _placeStep(),
                    _ => _detailStep(),
                  },
                ],
              ),
            ),
            BottomActions(
              children: [
                FilledButton(
                  key: const Key('reportNext'),
                  onPressed: _canContinue ? _next : null,
                  child: Text(_step < _steps ? 'Continuer →' : 'Enregistrer'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _typeStep() => [
    for (final k in reportKinds)
      OptionButton(
        key: Key('kind-${k.category ?? k.type}'),
        icon: k.icon,
        label: k.label,
        selected: _kind == k,
        onTap: () => setState(() => _kind = k),
      ),
  ];

  List<Widget> _placeStep() => [
    OutlinedButton.icon(
      key: const Key('scanPlace'),
      onPressed: _scan,
      icon: const Icon(Icons.qr_code_scanner, size: 26),
      label: const Text('Scanner le QR de la zone'),
    ),
    const SizedBox(height: 12),
    if (_zone != null)
      Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Text(
          'Zone détectée : $_lieu',
          key: const Key('detectedZone'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
    const Text('Ou choisissez le site :', style: TextStyle(fontSize: 16)),
    const SizedBox(height: 8),
    for (final s in _sites)
      OptionButton(
        key: Key('site-${s.code}'),
        icon: '⌖',
        label: s.name,
        selected: _site?.id == s.id,
        onTap: () => setState(() {
          _site = s;
          _zone = null;
        }),
      ),
  ];

  List<Widget> _detailStep() => [
    Row(
      children: [
        Expanded(
          child: OutlinedButton.icon(
            key: const Key('takePhoto'),
            onPressed: _takePhoto,
            icon: const Icon(Icons.photo_camera, size: 26),
            label: Text(
              _photos.isEmpty ? 'Photo' : 'Photo (${_photos.length})',
            ),
          ),
        ),
      ],
    ),
    if (_photos.isNotEmpty)
      Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final p in _photos)
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  border: Border.all(
                    color: Theme.of(context).colorScheme.onSurface,
                    width: 2,
                  ),
                ),
                child: Image.file(
                  File(p),
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const Icon(Icons.image),
                ),
              ),
          ],
        ),
      ),
    const SizedBox(height: 12),
    TextField(
      key: const Key('reportDescription'),
      controller: _description,
      minLines: 3,
      maxLines: 6,
      maxLength: 2000,
      style: const TextStyle(fontSize: 18),
      decoration: const InputDecoration(
        border: OutlineInputBorder(),
        labelText: 'Description',
        helperText: '🎙 Touchez le micro du clavier pour dicter.',
      ),
    ),
    const SizedBox(height: 8),
    const Text(
      'Est-ce dangereux maintenant ?',
      style: TextStyle(fontSize: 19, fontWeight: FontWeight.w700),
    ),
    const SizedBox(height: 8),
    for (final d in dangers)
      OptionButton(
        key: Key('danger-${d.severity}'),
        icon: d.icon,
        label: d.label,
        selected: _danger == d,
        onTap: () => setState(() => _danger = d),
      ),
  ];
}

class _Confirmation extends StatelessWidget {
  const _Confirmation({required this.online});
  final bool? online;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.check_circle, size: 72),
                      const SizedBox(height: 12),
                      const Text(
                        'Signalement enregistré',
                        style: TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        online == true
                            ? 'Envoi en cours au serveur'
                            : 'Sera envoyé dès le retour du réseau',
                        style: const TextStyle(fontSize: 17),
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                ),
              ),
            ),
            BottomActions(
              children: [
                FilledButton(
                  key: const Key('reportDone'),
                  onPressed: () => context.go('/'),
                  child: const Text('Terminé'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
