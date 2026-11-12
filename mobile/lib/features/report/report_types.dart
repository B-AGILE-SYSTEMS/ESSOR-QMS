/// Choix proposés à l'étape 1 du signalement (moteur générique M1/M2).
class ReportKind {
  const ReportKind(this.label, this.icon, this.type, {this.category});
  final String label;
  final String icon;

  /// Type de fiche : `NC` ou `INCIDENT`.
  final String type;

  /// Catégorie d'incident (`data.categorie`).
  final String? category;
}

const reportKinds = [
  ReportKind('Produit / qualité', '⚙', 'NC'),
  ReportKind('Presqu\'accident', '⚠', 'INCIDENT', category: 'presqu_accident'),
  ReportKind(
    'Accident du travail',
    '⛑',
    'INCIDENT',
    category: 'accident_travail',
  ),
  ReportKind(
    'Situation dangereuse',
    '▲',
    'INCIDENT',
    category: 'situation_dangereuse',
  ),
  ReportKind('Environnement', '♻', 'INCIDENT', category: 'environnement'),
  ReportKind('Équipement en panne', '⛟', 'INCIDENT', category: 'materiel'),
];

/// Réponse à « Est-ce dangereux maintenant ? » → gravité du contrat.
class Danger {
  const Danger(this.label, this.icon, this.severity);
  final String label;
  final String icon;
  final String severity;
}

const dangers = [
  Danger('Oui — arrêt immédiat', '▲', 'critique'),
  Danger('Non, mais à traiter vite', '◆', 'majeure'),
  Danger('Non, pour information', '●', 'mineure'),
];
