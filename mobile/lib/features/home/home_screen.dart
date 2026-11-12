import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/api/models.dart';
import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/theme/app_theme.dart';
import 'package:essor_mobile/features/home/sync_indicator.dart';

/// Accueil terrain (maquette mobile, écran 1). Actions métiers simulées : M2.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    if (session is! Authenticated) return const SizedBox.shrink();
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SyncStatusBar(),
            _Header(session: session),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: const [
                  PendingBanner(),
                  _Tile(
                    title: 'Audit 5S — ligne 2',
                    subtitle: '12 questions · à faire aujourd\'hui',
                  ),
                  _Tile(
                    title: 'Action : contrôle compteur outil',
                    subtitle: 'Échéance 20/11 · PR-USI-07',
                  ),
                ],
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
          TenantLogo(url: session.logoUrl, tenant: me.tenant),
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
  const TenantLogo({super.key, required this.url, required this.tenant});
  final String? url;
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
    final logo = url;
    return SizedBox.square(
      dimension: 48,
      child: logo == null
          ? fallback
          : Image.network(
              logo,
              fit: BoxFit.contain,
              errorBuilder: (_, _, _) => fallback,
            ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.title, required this.subtitle});
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.onSurface, width: 2),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          Text(subtitle, style: const TextStyle(fontSize: 15)),
        ],
      ),
    );
  }
}

class _Footer extends ConsumerWidget {
  const _Footer();

  /// Simulation M2 : l'action est mise en file locale.
  Future<void> _simulate(
    BuildContext context,
    WidgetRef ref,
    String kind,
    String message,
  ) async {
    await ref.read(syncDatabaseProvider).enqueue(kind, '{"simule":true}');
    unawaited(ref.read(syncServiceProvider).syncNow());
    if (!context.mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 18),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(top: BorderSide(color: scheme.outlineVariant)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          FilledButton.icon(
            key: const Key('reportButton'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(tapBigHeight),
              textStyle: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w700,
              ),
            ),
            onPressed: () => _simulate(
              context,
              ref,
              'nc.signaler',
              'Signalement enregistré (simulation M2)',
            ),
            icon: const Icon(Icons.warning_amber_rounded, size: 28),
            label: const Text('Signaler un problème'),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            key: const Key('scanButton'),
            onPressed: () => _simulate(
              context,
              ref,
              'instrument.scanner',
              'Scan enregistré (simulation M2)',
            ),
            icon: const Icon(Icons.qr_code_scanner, size: 26),
            label: const Text('Scanner un instrument'),
          ),
        ],
      ),
    );
  }
}
