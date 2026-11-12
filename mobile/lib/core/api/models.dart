/// Modèles du contrat M0 (`GET /api/me`, `GET /api/tenant/branding`).
class NamedRef {
  const NamedRef({required this.id, required this.code, required this.name});
  final String id;
  final String code;
  final String name;

  factory NamedRef.fromJson(Map<String, dynamic> j) => NamedRef(
    id: '${j['id']}',
    code: j['code'] as String? ?? '',
    name: j['name'] as String? ?? '',
  );
}

class Me {
  const Me({
    required this.username,
    required this.displayName,
    required this.tenant,
    required this.sites,
    required this.roles,
  });
  final String username;
  final String displayName;
  final NamedRef tenant;
  final List<NamedRef> sites;
  final List<String> roles;

  factory Me.fromJson(Map<String, dynamic> j) {
    final username = j['username'] as String;
    return Me(
      username: username,
      displayName: j['displayName'] as String? ?? username,
      tenant: NamedRef.fromJson(j['tenant'] as Map<String, dynamic>),
      sites: List.unmodifiable([
        for (final s in (j['sites'] as List<dynamic>? ?? const []))
          NamedRef.fromJson(s as Map<String, dynamic>),
      ]),
      roles: List.unmodifiable(
        (j['roles'] as List<dynamic>? ?? const []).cast<String>(),
      ),
    );
  }
}

enum AppTheme { clair, sombre, terrain }

class Branding {
  const Branding({
    this.logoUrl,
    this.primaryColor,
    this.theme = AppTheme.clair,
  });
  final String? logoUrl;
  final String? primaryColor;
  final AppTheme theme;

  static const fallback = Branding();

  factory Branding.fromJson(Map<String, dynamic> j) => Branding(
    logoUrl: j['logoUrl'] as String?,
    primaryColor: j['primaryColor'] as String?,
    theme: AppTheme.values.firstWhere(
      (t) => t.name == j['theme'],
      orElse: () => AppTheme.clair,
    ),
  );
}
