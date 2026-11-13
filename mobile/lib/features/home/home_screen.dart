import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/media/device_services.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/scan/qr_location.dart';
import 'package:essor_mobile/core/widgets/terrain.dart';
import 'package:essor_mobile/core/theme/app_theme.dart';
import 'package:essor_mobile/features/audits/audits_screen.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Accueil terrain (maquette mobile, écran 1). Tuiles : « mes tâches » en cache.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    if (session is! Authenticated) return const SizedBox.shrink();
    final tasks = ref.watch(tasksProvider).value ?? const [];
    final audits = ref.watch(auditsProvider).value ?? const [];
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            _Header(session: session),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const PendingBanner(),
                    const ReviewBanner(),
                    for (final a in audits.take(1)) AuditTile(audit: a),
                    for (final t in tasks.take(3))
                      _Tile(
                        key: Key('tile-${t.id}'),
                        title: t.title,
                        subtitle: [
                          t.reference,
                          if (t.dueDate != null) 'Échéance ${t.dueDate}',
                          t.statusLabel,
                        ].join(' · '),
                        onTap: () => context.go('/fiche/${t.id}'),
                      ),
                    if (tasks.isEmpty && audits.isEmpty)
                      const Text(
                        'Aucune tâche en cache pour l\'instant.',
                        style: TextStyle(fontSize: 16),
                      ),
                  ],
                ),
              ),
            ),
            const _Footer(),
          ],
        ),
      ),
    );
  }
}

class _Header extends ConsumerWidget {
  const _Header({required this.session});
  final Authenticated session;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final me = session.me;
    final site = me.sites.isEmpty ? me.tenant.name : me.sites.first.name;
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(bottom: BorderSide(color: scheme.onSurface, width: 2)),
      ),
      child: Row(
        children: [
          TenantLogo(
            url: session.logoUrl,
            tenant: me.tenant,
            bytes: ref.watch(logoBytesProvider).value,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Bonjour ${me.displayName}',
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(site, style: const TextStyle(fontSize: 14)),
              ],
            ),
          ),
          IconButton(
            tooltip: 'Se déconnecter',
            iconSize: 28,
            onPressed: ref.read(sessionProvider.notifier).logout,
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
    );
  }
}

/// Logo de l'exploitant, ou ses initiales sur la couleur principale.
class TenantLogo extends StatelessWidget {
  const TenantLogo({
    super.key,
    required this.url,
    required this.tenant,
    this.bytes,
  });
  final String? url;

  /// Logo en cache local (prioritaire, disponible hors ligne).
  final Uint8List? bytes;
  final NamedRef tenant;

  @override
  Widget build(BuildContext context) {
    final primary = Theme.of(context).colorScheme.primary;
    final code = tenant.code.isEmpty ? tenant.name : tenant.code;
    final initials = code.length >= 2 ? code.substring(0, 2) : code;
    final fallback = Container(
      color: primary,
      alignment: Alignment.center,
      child: Text(
        initials.toUpperCase(),
        style: TextStyle(color: onColor(primary), fontWeight: FontWeight.w800),
      ),
    );
    final cached = bytes;
    return SizedBox.square(
      dimension: 48,
      child: cached != null && cached.isNotEmpty
          ? Image.memory(
              cached,
              fit: BoxFit.contain,
              errorBuilder: (_, _, _) => fallback,
            )
          : fallback,
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({
    super.key,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.onSurface, width: 2),
      ),
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                ),
              ),
              Text(subtitle, style: const TextStyle(fontSize: 15)),
            ],
          ),
        ),
      ),
    );
  }
}

class _Footer extends ConsumerWidget {
  const _Footer();

  Future<void> _scan(BuildContext context, WidgetRef ref) async {
    final raw = await ref.read(qrScannerProvider)(context);
    if (!context.mounted || raw == null) return;
    if (parseQrLocation(raw) == null) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('QR code non reconnu')));
      return;
    }
    context.go(Uri(path: '/signaler', queryParameters: {'qr': raw}).toString());
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return BottomActions(
      children: [
        OutlinedButton.icon(
          key: const Key('controlButton'),
          onPressed: () => context.go('/controle'),
          icon: const Icon(Icons.inventory_2, size: 26),
          label: const Text('Contrôler un lot'),
        ),
        FilledButton.icon(
          key: const Key('reportButton'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(tapBigHeight),
            textStyle: Theme.of(context).textTheme.labelLarge
                ?.copyWith(fontSize: 20, fontWeight: FontWeight.w700),
          ),
          onPressed: () => context.go('/signaler'),
          icon: const Icon(Icons.warning_amber_rounded, size: 28),
          label: const Text('Signaler un problème'),
        ),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('tasksButton'),
                onPressed: () => context.go('/taches'),
                icon: const Icon(Icons.checklist, size: 26),
                label: const Text('Mes tâches'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('scanButton'),
                onPressed: () => _scan(context, ref),
                icon: const Icon(Icons.qr_code_scanner, size: 26),
                label: const Text('Scanner'),
              ),
            ),
          ],
        ),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('auditsButton'),
                onPressed: () => context.go('/audits'),
                icon: const Icon(Icons.fact_check, size: 26),
                label: const Text('Mes audits'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('documentsButton'),
                onPressed: () => context.go('/documents'),
                icon: const Icon(Icons.menu_book, size: 26),
                label: const Text('Documents'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
