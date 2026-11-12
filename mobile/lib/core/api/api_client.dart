import 'package:dio/dio.dart';

import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';
import 'package:essor_mobile/core/api/models.dart';

/// Client HTTP : ajoute le Bearer et tente un renouvellement sur 401.
class ApiClient {
  ApiClient(this.server, this._auth, {Dio? dio})
    : _dio =
          dio ??
          Dio(
            BaseOptions(
              connectTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 20),
            ),
          ) {
    _dio.options.baseUrl = server.api;
    _dio.interceptors.add(
      QueuedInterceptorsWrapper(
        onRequest: (options, handler) async {
          final token = await _auth.accessToken();
          if (token != null) options.headers['Authorization'] = 'Bearer $token';
          handler.next(options);
        },
        onError: (e, handler) async {
          final alreadyRetried = e.requestOptions.extra['retried'] == true;
          if (e.response?.statusCode != 401 || alreadyRetried) {
            return handler.next(e);
          }
          if (!await _auth.refresh(server)) return handler.next(e);
          final opts = e.requestOptions..extra['retried'] = true;
          opts.headers['Authorization'] = 'Bearer ${await _auth.accessToken()}';
          try {
            handler.resolve(await _dio.fetch<dynamic>(opts));
          } on DioException catch (err) {
            handler.next(err);
          }
        },
      ),
    );
  }

  final ServerUrl server;
  final AuthService _auth;
  final Dio _dio;

  Dio get dio => _dio;

  Future<Me> me() async {
    final r = await _dio.get<Map<String, dynamic>>('/me');
    return Me.fromJson(r.data!);
  }

  Future<Branding> branding() async {
    final r = await _dio.get<Map<String, dynamic>>('/tenant/branding');
    return Branding.fromJson(r.data!);
  }

  /// URL absolue du logo (le contrat peut renvoyer un chemin relatif).
  String? logoUri(Branding b) {
    final l = b.logoUrl;
    if (l == null || l.isEmpty) return null;
    return Uri.parse('${server.base}/').resolve(l).toString();
  }
}
