import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/providers.dart';

/// Premier lancement : saisie de l'URL du serveur on-premise.
class ServerScreen extends ConsumerStatefulWidget {
  const ServerScreen({super.key});

  @override
  ConsumerState<ServerScreen> createState() => _ServerScreenState();
}

class _ServerScreenState extends ConsumerState<ServerScreen> {
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final url = ServerUrl.parse(_controller.text);
    if (url == null) {
      setState(() => _error = 'Adresse invalide (ex. https://qms.usine.local)');
      return;
    }
    await ref.read(sessionProvider.notifier).setServer(url);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('ESSOR QMS')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Adresse du serveur de votre entreprise',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),
              TextField(
                key: const Key('serverUrl'),
                controller: _controller,
                keyboardType: TextInputType.url,
                autocorrect: false,
                style: const TextStyle(fontSize: 18),
                decoration: InputDecoration(
                  border: const OutlineInputBorder(),
                  hintText: 'https://qms.usine.local',
                  errorText: _error,
                ),
                onSubmitted: (_) => _submit(),
              ),
              const Spacer(),
              FilledButton(
                key: const Key('serverSubmit'),
                onPressed: _submit,
                child: const Text('Continuer'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
