import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/m4/instruments.dart';

String _date(DateTime d) =>
    '${d.day.toString().padLeft(2, '0')}/'
    '${d.month.toString().padLeft(2, '0')}/${d.year}';

/// « Échéance 28/11/2026 (J-15) », « Échu depuis le … », ou inconnue.
String dueLabel(Instrument i, DateTime now) {
  final due = i.nextDue;
  final left = i.daysLeft(now);
  if (due == null || left == null) return 'Échéance d\'étalonnage inconnue';
  if (left < 0) return 'Étalonnage échu depuis le ${_date(due)}';
  return 'Échéance ${_date(due)} (J-$left)';
}

/// Fiche instrument : statut, échéance, et refus bien visible.
class InstrumentCard extends StatelessWidget {
  const InstrumentCard({
    super.key,
    required this.instrument,
    required this.now,
  });
  final Instrument instrument;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final i = instrument;
    final scheme = Theme.of(context).colorScheme;
    final refusal = i.refusal(now);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '${i.code} · ${i.designation}',
          key: const Key('instrumentTitle'),
          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 6),
        Text(
          'Statut : ${i.statusLabel}',
          key: const Key('instrumentStatus'),
          style: const TextStyle(fontSize: 18),
        ),
        Text(
          dueLabel(i, now),
          key: const Key('instrumentDue'),
          style: const TextStyle(fontSize: 18),
        ),
        for (final line in [
          if (i.type != null) 'Type : ${i.type}',
          if (i.site != null) 'Site : ${i.site}',
          if (i.location != null) 'Emplacement : ${i.location}',
          if (i.emt != null) 'EMT : ${i.emt}',
        ])
          Text(line, style: const TextStyle(fontSize: 16)),
        const SizedBox(height: 12),
        Container(
          key: Key(refusal == null ? 'instrumentOk' : 'instrumentBlocked'),
          padding: const EdgeInsets.all(14),
          color: refusal == null ? scheme.primaryContainer : scheme.error,
          child: Text(
            refusal ?? 'Utilisable pour les mesures.',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w700,
              color: refusal == null
                  ? scheme.onPrimaryContainer
                  : scheme.onError,
            ),
          ),
        ),
      ],
    );
  }
}
