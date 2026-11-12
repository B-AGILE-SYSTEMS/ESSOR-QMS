import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/providers.dart';
import 'package:essor_mobile/core/theme/app_theme.dart';
import 'package:essor_mobile/features/home/home_screen.dart';
import 'package:essor_mobile/features/setup/login_screen.dart';
import 'package:essor_mobile/features/setup/server_screen.dart';

/// Route imposée par l'état de session, `null` si la route courante convient.
String? redirectFor(SessionState s, String location) {
  final target = switch (s) {
    SessionLoading() => '/chargement',
    NeedsServer() => '/serveur',
    NeedsLogin() => '/connexion',
    Authenticated() => '/',
  };
  return target == location ? null : target;
}

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = ValueNotifier<SessionState>(ref.read(sessionProvider));
  ref.listen(sessionProvider, (_, next) => refresh.value = next);
  final router = GoRouter(
    initialLocation: '/chargement',
    refreshListenable: refresh,
    redirect: (_, state) => redirectFor(refresh.value, state.matchedLocation),
    routes: [
      GoRoute(
        path: '/chargement',
        builder: (_, _) =>
            const Scaffold(body: Center(child: CircularProgressIndicator())),
      ),
      GoRoute(path: '/serveur', builder: (_, _) => const ServerScreen()),
      GoRoute(path: '/connexion', builder: (_, _) => const LoginScreen()),
      GoRoute(path: '/', builder: (_, _) => const HomeScreen()),
    ],
  );
  ref.onDispose(() {
    router.dispose();
    refresh.dispose();
  });
  return router;
});

class EssorApp extends ConsumerWidget {
  const EssorApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      title: 'ESSOR QMS',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(ref.watch(brandingProvider)),
      routerConfig: ref.watch(routerProvider),
    );
  }
}
