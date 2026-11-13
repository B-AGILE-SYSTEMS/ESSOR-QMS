import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';

import 'package:essor_mobile/core/m3/audits_repository.dart';
import 'package:essor_mobile/core/m3/m3_api.dart';
import 'package:essor_mobile/core/m3/models.dart';
import 'package:essor_mobile/core/sync/operations.dart';
import 'package:essor_mobile/core/sync/sync_database.dart';

const documentsCacheKey = 'm3.documents';

/// Documents en vigueur téléchargés pour lecture hors ligne ;
/// accusé de lecture mis en file (différé si hors ligne).
class DocumentsRepository {
  DocumentsRepository(this._db, this._queue);
  final SyncDatabase _db;
  final OperationQueue _queue;

  Stream<List<DocumentItem>> watch() => _db
      .watchCache(documentsCacheKey)
      .map((raw) => decodeList(raw, DocumentItem.fromJson));

  Future<List<DocumentItem>> current() async =>
      decodeList(await _db.getCache(documentsCacheKey), DocumentItem.fromJson);

  Future<void> _save(List<DocumentItem> docs) => _db.putCache(
    documentsCacheKey,
    jsonEncode([for (final d in docs) d.toJson()]),
  );

  /// Recharge la liste, télécharge les versions absentes du téléphone et
  /// supprime les copies périmées. Un accusé encore en file reste acquis.
  /// Deux cycles de synchro simultanés partagent le même rafraîchissement.
  Future<void> refresh(M3Api api, Directory dir) {
    final running = _refreshing;
    if (running != null) return running;
    return _refreshing = _refresh(
      api,
      dir,
    ).whenComplete(() => _refreshing = null);
  }

  Future<void>? _refreshing;

  Future<void> _refresh(M3Api api, Directory dir) async {
    final remote = await api.documentsInForce();
    final local = {for (final d in await current()) d.id: d};
    final result = <DocumentItem>[];
    for (final d in remote) {
      final old = local[d.id];
      final sameVersion = old != null && old.version == d.version;
      var path = sameVersion ? old.localPath : null;
      if (path == null || !File(path).existsSync()) {
        path = await _download(api, dir, d);
      }
      if (!sameVersion) _deleteFile(old?.localPath);
      result.add(
        d.copyWith(
          localPath: path,
          acked: d.acked || (sameVersion && old.acked),
        ),
      );
    }
    final kept = {for (final d in remote) d.id};
    for (final old in local.values) {
      if (!kept.contains(old.id)) _deleteFile(old.localPath);
    }
    await _save(result);
  }

  Future<String?> _download(M3Api api, Directory dir, DocumentItem d) async {
    try {
      final bytes = await api.documentFile(d);
      if (bytes.isEmpty) return null;
      final ext = _extension(d.fileName);
      final f = File('${dir.path}/${_safe(d.id)}_${_safe(d.version)}$ext');
      await f.writeAsBytes(bytes, flush: true);
      return f.path;
    } on DioException {
      return null;
    }
  }

  /// « J'ai lu » : enregistré tout de suite, envoyé par la file
  /// (`POST /api/documents/{id}/versions/{v}/ack`).
  Future<void> acknowledge(DocumentItem doc) => _db.transaction(() async {
    await _queue.documentAck(documentId: doc.id, version: doc.version);
    await _save([
      for (final d in await current())
        d.id == doc.id && d.version == doc.version
            ? d.copyWith(acked: true)
            : d,
    ]);
  });

  static void _deleteFile(String? path) {
    if (path == null) return;
    final f = File(path);
    if (f.existsSync()) f.deleteSync();
  }

  static String _safe(String s) => s.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');

  static String _extension(String? name) {
    final m = RegExp(
      r'\.(pdf|docx|xlsx)$',
      caseSensitive: false,
    ).firstMatch(name ?? '');
    return m == null ? '.pdf' : '.${m.group(1)!.toLowerCase()}';
  }
}
