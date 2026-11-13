/// Modèles du contrat M3 (audits et gestion documentaire) côté mobile.
library;

String? _name(Object? v) => v is Map ? v['name'] as String? : v as String?;

/// Résultats d'une question d'audit (contrat M3).
abstract final class AuditResult {
  static const conforme = 'conforme';
  static const ecartMineur = 'ecart_mineur';
  static const ecartMajeur = 'ecart_majeur';
  static const observation = 'observation';
  static const na = 'na';

  /// Ordre d'affichage : (code, pictogramme, libellé).
  static const options = [
    (conforme, '✓', 'Conforme'),
    (ecartMineur, '◆', 'Écart mineur'),
    (ecartMajeur, '✕', 'Écart majeur'),
    (observation, '●', 'Observation'),
    (na, '–', 'N.A.'),
  ];

  static bool isGap(String? r) => r == ecartMineur || r == ecartMajeur;
}

/// Audit à exécuter (fiche `AUDIT`, `GET /api/audits?year=`).
class AuditSummary {
  const AuditSummary({
    required this.id,
    required this.reference,
    required this.title,
    required this.status,
    this.siteName,
    this.plannedDate,
  });
  final String id;
  final String reference;
  final String title;
  final String status;
  final String? siteName;
  final String? plannedDate;

  /// Seuls ces statuts s'exécutent sur le terrain.
  bool get executable => status == 'PLANIFIE' || status == 'EN_COURS';

  factory AuditSummary.fromJson(Map<String, dynamic> j) => AuditSummary(
    id: '${j['id']}',
    reference: j['reference'] as String? ?? '',
    title: j['title'] as String? ?? '',
    status: j['status'] as String? ?? '',
    siteName: j['siteName'] as String? ?? _name(j['site']),
    plannedDate: j['plannedDate'] as String? ?? j['dueDate'] as String?,
  );

  Map<String, Object?> toJson() => {
    'id': id,
    'reference': reference,
    'title': title,
    'status': status,
    'siteName': siteName,
    'plannedDate': plannedDate,
  };
}

/// Question de la grille (une par exigence auditable).
class AuditQuestion {
  const AuditQuestion({
    required this.id,
    required this.text,
    this.requirementCode,
    this.requirementTitle,
  });
  final String id;
  final String text;
  final String? requirementCode;
  final String? requirementTitle;

  factory AuditQuestion.fromJson(Map<String, dynamic> j) {
    final req = j['requirement'];
    return AuditQuestion(
      id: '${j['id']}',
      text: j['text'] as String? ?? j['question'] as String? ?? '',
      requirementCode:
          j['requirementCode'] as String? ??
          (req is Map ? req['code'] as String? : null),
      requirementTitle:
          j['requirementTitle'] as String? ??
          (req is Map ? req['title'] as String? : null),
    );
  }

  Map<String, Object?> toJson() => {
    'id': id,
    'text': text,
    'requirementCode': requirementCode,
    'requirementTitle': requirementTitle,
  };
}

/// Réponse saisie sur le téléphone.
class AuditAnswer {
  const AuditAnswer({required this.result, this.comment, this.photos = 0});
  final String result;
  final String? comment;
  final int photos;

  factory AuditAnswer.fromJson(Map<String, dynamic> j) => AuditAnswer(
    result: j['result'] as String,
    comment: j['comment'] as String?,
    photos: j['photos'] as int? ?? 0,
  );

  Map<String, Object?> toJson() => {
    'result': result,
    'comment': comment,
    'photos': photos,
  };
}

/// Document en vigueur (version diffusée) consultable hors ligne.
class DocumentItem {
  const DocumentItem({
    required this.id,
    required this.code,
    required this.title,
    required this.version,
    this.type,
    this.versionLabel,
    this.fileName,
    this.localPath,
    this.acked = false,
  });
  final String id;
  final String code;
  final String title;
  final String? type;

  /// Identifiant de la version en vigueur (`{v}` des URL du contrat).
  final String version;
  final String? versionLabel;
  final String? fileName;

  /// Copie locale téléchargée (lecture hors ligne).
  final String? localPath;
  final bool acked;

  factory DocumentItem.fromJson(Map<String, dynamic> j) {
    final cur = j['currentVersion'];
    final v = cur is Map ? cur : const <String, dynamic>{};
    return DocumentItem(
      id: '${j['id']}',
      code: j['code'] as String? ?? '',
      title: j['title'] as String? ?? '',
      type: j['type'] as String?,
      version: '${v['id'] ?? v['number'] ?? j['version'] ?? ''}',
      versionLabel:
          v['label'] as String? ??
          j['versionLabel'] as String? ??
          (v['number'] == null ? null : 'v${v['number']}'),
      fileName: v['fileName'] as String? ?? j['fileName'] as String?,
      localPath: j['localPath'] as String?,
      acked: j['acked'] as bool? ?? v['acked'] as bool? ?? false,
    );
  }

  DocumentItem copyWith({String? localPath, bool? acked}) => DocumentItem(
    id: id,
    code: code,
    title: title,
    type: type,
    version: version,
    versionLabel: versionLabel,
    fileName: fileName,
    localPath: localPath ?? this.localPath,
    acked: acked ?? this.acked,
  );

  Map<String, Object?> toJson() => {
    'id': id,
    'code': code,
    'title': title,
    'type': type,
    'version': version,
    'versionLabel': versionLabel,
    'fileName': fileName,
    'localPath': localPath,
    'acked': acked,
  };
}
