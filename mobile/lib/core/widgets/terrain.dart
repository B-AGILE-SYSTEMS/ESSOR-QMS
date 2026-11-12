import 'package:material_ui/material_ui.dart';

/// Cible tactile minimale imposée par la carte M2.
const minTarget = 56.0;

/// Bouton d'option plein largeur (maquette `.opt`), sélection contrastée.
class OptionButton extends StatelessWidget {
  const OptionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });
  final String icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = selected ? scheme.surface : scheme.onSurface;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Semantics(
        selected: selected,
        button: true,
        child: Material(
          color: selected ? scheme.onSurface : scheme.surface,
          shape: RoundedRectangleBorder(
            side: BorderSide(
              color: selected ? scheme.onSurface : scheme.outline,
              width: 2,
            ),
          ),
          child: InkWell(
            onTap: onTap,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 64),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                child: Row(
                  children: [
                    SizedBox(
                      width: 30,
                      child: Text(
                        icon,
                        style: TextStyle(fontSize: 24, color: fg),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        label,
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          color: fg,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Barre de progression des étapes (maquette `.steps`).
class StepBar extends StatelessWidget {
  const StepBar({super.key, required this.step, required this.total});
  final int step;
  final int total;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        for (var i = 1; i <= total; i++) ...[
          if (i > 1) const SizedBox(width: 6),
          Expanded(
            child: Container(
              height: 8,
              color: i <= step ? scheme.primary : scheme.outlineVariant,
            ),
          ),
        ],
      ],
    );
  }
}

/// Zone d'actions en bas d'écran (maquette `.mfoot`).
class BottomActions extends StatelessWidget {
  const BottomActions({super.key, required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 18),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(top: BorderSide(color: scheme.outlineVariant)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) const SizedBox(height: 10),
            children[i],
          ],
        ],
      ),
    );
  }
}

/// En-tête d'écran avec retour (maquette `.mhead`).
class ScreenHeader extends StatelessWidget {
  const ScreenHeader({
    super.key,
    required this.title,
    this.subtitle,
    this.onBack,
  });
  final String title;
  final String? subtitle;
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(bottom: BorderSide(color: scheme.onSurface, width: 2)),
      ),
      child: Row(
        children: [
          if (onBack != null)
            SizedBox.square(
              dimension: minTarget,
              child: IconButton(
                key: const Key('backButton'),
                tooltip: 'Retour',
                iconSize: 28,
                onPressed: onBack,
                icon: const Icon(Icons.arrow_back),
              ),
            ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (subtitle != null)
                  Text(subtitle!, style: const TextStyle(fontSize: 14)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
