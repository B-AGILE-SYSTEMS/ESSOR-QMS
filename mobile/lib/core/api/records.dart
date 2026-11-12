/// Modèles du contrat M1/M2 utilisés par le mobile.
class RecordSummary {
  const RecordSummary({
    required this.id,
    required this.reference,
    required this.type,
    required this.status,
    required this.statusLabel,
    required this.title,
    this.severity,
    this.siteName,
    this.ownerName,
    this.dueDate,
    this.overdue = false,
    this.updatedAt,
  });
  final String? updatedAt;
  final String id;
  final String reference;
  final String type;
  final String status;
  final String statusLabel;
  final String title;
  final String? severity;
  final String? siteName;
  final String? ownerName;
  final String? dueDate;
  final bool overdue;

  factory RecordSummary.fromJson(Map<String, dynamic> j) => RecordSummary(
    id: '${j['id']}',
    reference: j['reference'] as String? ?? '',
    type: j['type'] as String? ?? j['recordType'] as String? ?? '',
    status: j['status'] as String? ?? '',
    statusLabel: j['statusLabel'] as String? ?? j['status'] as String? ?? '',
    title: j['title'] as String? ?? '',
    severity: j['severity'] as String?,
    siteName: j['siteName'] as String?,
    ownerName: j['ownerName'] as String?,
    dueDate: j['dueDate'] as String?,
    overdue: j['overdue'] as bool? ?? false,
    updatedAt: j['updatedAt'] as String?,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'reference': reference,
    'type': type,
    'status': status,
    'statusLabel': statusLabel,
    'title': title,
    'severity': severity,
    'siteName': siteName,
    'ownerName': ownerName,
    'dueDate': dueDate,
    'overdue': overdue,
    'updatedAt': updatedAt,
  };

  RecordSummary withStatus(String status, String label) => RecordSummary(
    id: id,
    reference: reference,
    type: type,
    status: status,
    statusLabel: label,
    title: title,
    severity: severity,
    siteName: siteName,
    ownerName: ownerName,
    dueDate: dueDate,
    overdue: overdue,
    updatedAt: updatedAt,
  );
}

/// Fiche détaillée (`GET /api/records/{id}`), lecture seule sur mobile.
class RecordDetail {
  const RecordDetail(this.summary, {this.description, this.data = const {}});
  final RecordSummary summary;
  final String? description;
  final Map<String, dynamic> data;

  factory RecordDetail.fromJson(Map<String, dynamic> j) => RecordDetail(
    RecordSummary.fromJson(j),
    description: j['description'] as String?,
    data: Map.unmodifiable(j['data'] as Map<String, dynamic>? ?? const {}),
  );
}
