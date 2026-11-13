import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m3b/models.dart';
import 'package:essor_mobile/core/m4/instruments.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/features/instruments/instrument_card.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';

/// Un écran par caractéristique : valeurs mesurées et verdict automatique
/// selon les tolérances, ou choix conforme / non conforme.
class CharacteristicStep extends ConsumerStatefulWidget {
  const CharacteristicStep({
    super.key,
    required this.characteristic,
    required this.onChanged,
    this.initial,
    this.sampleSize = 1,
  });
  final Characteristic characteristic;
  final CharResult? initial;
  final int sampleSize;
  final ValueChanged<CharResult> onChanged;

  @override
  ConsumerState<CharacteristicStep> createState() => _CharacteristicStepState();
}

class _CharacteristicStepState extends ConsumerState<CharacteristicStep> {
  late final List<TextEditingController> _values;

  /// Instrument choisi (code, id M4) ; seuls en service et non échus.
  String? _instCode;
  String? _instId;
  String? _instMessage;
  bool? _attr;

  Characteristic get _c => widget.characteristic;

  @override
  void initState() {
    super.initState();
    final init = widget.initial;
    final previous = [
      for (final v in init?.values ?? const <Object>[])
        if (v is num) '$v'.replaceAll('.', ','),
    ];
    final n = previous.isNotEmpty ? previous.length : widget.sampleSize;
    _values = [
      for (var i = 0; i < (n < 1 ? 1 : n); i++)
        TextEditingController(text: i < previous.length ? previous[i] : ''),
    ];
    _instCode = init?.instrument;
    _instId = init?.instrumentId;
    if (_c.type != CharType.mesure) _attr = init?.ok;
  }

  @override
  void dispose() {
    for (final c in _values) {
      c.dispose();
    }
    super.dispose();
  }

  List<double?> get _parsed => [
    for (final c in _values)
      double.tryParse(c.text.trim().replaceAll(',', '.')),
  ];

  void _emit() {
    final inst = _instCode;
    if (_c.type == CharType.mesure) {
      final values = _parsed;
      final ok = measureVerdict(_c, values);
      if (ok == null || (_c.instrumentRequis != null && inst == null)) return;
      widget.onChanged(
        CharResult(
          characteristic: _c.code,
          values: [for (final v in values) v!],
          ok: ok,
          instrument: inst,
          instrumentId: _instId,
        ),
      );
    } else {
      final ok = _attr;
      if (ok == null) return;
      widget.onChanged(
        CharResult(
          characteristic: _c.code,
          values: [ok],
          ok: ok,
          instrument: inst,
          instrumentId: _instId,
        ),
      );
    }
  }

  void _changed() {
    setState(() {});
    _emit();
  }

  @override
  Widget build(BuildContext context) {
    final c = _c;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '${c.code} · criticité ${c.criticite}',
          style: const TextStyle(fontSize: 14),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Text(
            c.libelle,
            key: const Key('charLabel'),
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
          ),
        ),
        if (c.type == CharType.mesure) ..._measure(c) else ..._attribute(c),
        if (c.instrumentRequis case final required?) ...[
          const SizedBox(height: 10),
          Text(
            'Instrument requis : $required',
            key: const Key('instrumentRequired'),
          ),
          const SizedBox(height: 6),
          ..._instrumentPicker(),
        ],
      ],
    );
  }

  List<Widget> _measure(Characteristic c) {
    final verdict = measureVerdict(c, _parsed);
    return [
      if (c.toleranceLabel.isNotEmpty)
        Text(
          c.toleranceLabel,
          key: const Key('tolerance'),
          style: const TextStyle(fontSize: 17),
        ),
      const SizedBox(height: 8),
      for (var i = 0; i < _values.length; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: TextField(
            key: Key('value-$i'),
            controller: _values[i],
            keyboardType: const TextInputType.numberWithOptions(
              decimal: true,
              signed: true,
            ),
            style: const TextStyle(fontSize: 20),
            onChanged: (_) => _changed(),
            decoration: InputDecoration(
              labelText:
                  'Valeur ${i + 1}${c.unite == null ? '' : ' (${c.unite})'}',
              border: const OutlineInputBorder(),
              errorText: _outOf(c, i) ? 'Hors tolérance' : null,
            ),
          ),
        ),
      OutlinedButton(
        key: const Key('addValue'),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(minTarget),
        ),
        onPressed: () => setState(() => _values.add(TextEditingController())),
        child: const Text('+ Ajouter une valeur'),
      ),
      const SizedBox(height: 10),
      Text(
        switch (verdict) {
          null => 'Saisissez toutes les valeurs',
          true => 'Verdict : Conforme ✓',
          false => 'Verdict : Hors tolérance ✕',
        },
        key: const Key('verdict'),
        style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
      ),
    ];
  }

  bool _outOf(Characteristic c, int i) {
    final v = _parsed[i];
    return v != null && !c.inTolerance(v);
  }

  List<Widget> _attribute(Characteristic c) {
    final (ok, nok) = c.type == CharType.document
        ? ('Document présent et conforme', 'Document absent ou non conforme')
        : ('Conforme', 'Non conforme');
    return [
      OptionButton(
        key: const Key('attr-ok'),
        icon: '✓',
        label: ok,
        selected: _attr == true,
        onTap: () {
          _attr = true;
          _changed();
        },
      ),
      OptionButton(
        key: const Key('attr-nok'),
        icon: '✕',
        label: nok,
        selected: _attr == false,
        onTap: () {
          _attr = false;
          _changed();
        },
      ),
    ];
  }

  DateTime get _now => ref.read(clockProvider)();

  void _select(Instrument i) {
    final refusal = i.refusal(_now);
    setState(() {
      _instMessage = refusal;
      if (refusal == null) {
        _instCode = i.code;
        _instId = i.id;
      }
    });
    if (refusal == null) _emit();
  }

  Future<void> _scanInstrument() async {
    final raw = await ref.read(instrumentScannerProvider)(context);
    final id = parseInstrumentQr(raw);
    if (id == null) return;
    final api = ref.read(apiClientProvider);
    final found = await ref
        .read(instrumentsRepositoryProvider)
        .find(id, api == null ? null : InstrumentsApi(api.dio));
    if (!mounted) return;
    if (found == null) {
      setState(() => _instMessage = 'Instrument inconnu sur le téléphone.');
      return;
    }
    _select(found);
  }

  Future<void> _pickInstrument() async {
    final usable = await ref.read(instrumentsRepositoryProvider).usable(_now);
    if (!mounted) return;
    if (usable.isEmpty) {
      setState(() => _instMessage = 'Aucun instrument en service et non échu.');
      return;
    }
    final chosen = await showModalBottomSheet<Instrument>(
      context: context,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            for (final i in usable)
              ListTile(
                key: Key('inst-${i.code}'),
                minTileHeight: 56,
                title: Text('${i.code} · ${i.designation}'),
                subtitle: Text(dueLabel(i, _now)),
                onTap: () => Navigator.of(ctx).pop(i),
              ),
          ],
        ),
      ),
    );
    if (chosen != null && mounted) _select(chosen);
  }

  List<Widget> _instrumentPicker() => [
    Text(
      _instCode == null
          ? 'Aucun instrument choisi (obligatoire)'
          : 'Instrument : $_instCode',
      key: const Key('instrumentChosen'),
      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
    ),
    const SizedBox(height: 6),
    Row(
      children: [
        Expanded(
          child: OutlinedButton.icon(
            key: const Key('instrumentScan'),
            style: OutlinedButton.styleFrom(minimumSize: const Size(0, 56)),
            onPressed: _scanInstrument,
            icon: const Icon(Icons.qr_code_scanner),
            label: const Text('Scanner'),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: OutlinedButton.icon(
            key: const Key('instrumentPick'),
            style: OutlinedButton.styleFrom(minimumSize: const Size(0, 56)),
            onPressed: _pickInstrument,
            icon: const Icon(Icons.list),
            label: const Text('Liste'),
          ),
        ),
      ],
    ),
    if (_instMessage case final m?)
      Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Text(
          m,
          key: const Key('instrumentRefusal'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
  ];
}
