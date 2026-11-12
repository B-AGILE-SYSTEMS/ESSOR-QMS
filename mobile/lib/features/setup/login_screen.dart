import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/providers.dart';

class LoginScreen extends ConsumerWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    final server = session is NeedsLogin ? session.server.toString() : '';
    final error = session is NeedsLogin ? session.error : null;
    final controller = ref.read(sessionProvider.notifier);
    return Scaffold(
      appBar: AppBar(title: const Text('Connexion')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Serveur : $server', style: const TextStyle(fontSize: 16)),
              if (error != null) ...[
                const SizedBox(height: 12),
                Text(
                  error,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.error,
                    fontSize: 16,
                  ),
                ),
              ],
              const Spacer(),
              FilledButton(
                key: const Key('loginButton'),
                onPressed: controller.login,
                child: const Text('Se connecter'),
              ),
              const SizedBox(height: 10),
              OutlinedButton(
                onPressed: controller.changeServer,
                child: const Text('Changer de serveur'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
