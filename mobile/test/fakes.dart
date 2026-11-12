import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'package:essor_mobile/core/auth/auth_service.dart';
import 'package:essor_mobile/core/config/server_config.dart';

class MemoryStore implements SecureStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async => values[key] = value;
  @override
  Future<void> delete(String key) async => values.remove(key);
}

class FakeOidc implements OidcClient {
  Tokens? next = const Tokens(accessToken: 'a1', refreshToken: 'r1');
  Tokens? nextRefresh = const Tokens(accessToken: 'a2', refreshToken: 'r2');
  bool throwOnRefresh = false;
  int refreshCalls = 0;

  @override
  Future<Tokens?> authorize(ServerUrl server) async => next;

  @override
  Future<Tokens?> refresh(ServerUrl server, String refreshToken) async {
    refreshCalls++;
    if (throwOnRefresh) throw Exception('réseau');
    return nextRefresh;
  }
}

typedef Responder = (int, Object?) Function(RequestOptions options);

/// Adaptateur Dio qui répond sans réseau.
class FakeAdapter implements HttpClientAdapter {
  FakeAdapter(this.respond);
  final Responder respond;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    final (status, body) = respond(options);
    return ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

const meJson = {
  'username': 'terrain',
  'displayName': 'Youssef',
  'tenant': {'id': 't1', 'code': 'AL', 'name': 'Atlas Industrie'},
  'sites': [
    {'id': 's1', 'code': 'KEN', 'name': 'Kénitra · Atelier usinage'},
  ],
  'roles': ['FIELD_USER'],
};

const brandingJson = {
  'logoUrl': '/api/tenant/branding/logo',
  'primaryColor': '#1D4ED8',
  'theme': 'terrain',
};
