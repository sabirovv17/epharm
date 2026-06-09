import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../config/api_config.dart';
import 'api_exception.dart';
import 'token_store.dart';

/// Тонкая обёртка над http.Client для backend Epharm.
///
/// Возможности:
///  - префикс baseUrl + JSON-кодирование;
///  - Bearer-токен из TokenStore на защищённых запросах;
///  - **JWT-refresh interceptor**: на 401 пытается обновить пару через
///    `/api/mobile/auth/refresh` и повторяет запрос один раз;
///  - единый разбор ошибок `{code,message}` → ApiException.
class ApiClient {
  ApiClient(
    this._tokenStore, {
    http.Client? client,
    String? baseUrl,
  })  : _client = client ?? http.Client(),
        _baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client _client;
  final TokenStore _tokenStore;
  final String _baseUrl;

  // ── Публичный API ──────────────────────────────────────────────────────────

  Future<Map<String, dynamic>> getJson(String path, {bool auth = true}) async {
    final res = await _sendWithRefresh(
      () => _client.get(_uri(path), headers: _headers(auth: auth)),
      auth: auth,
    );
    return _decode(res);
  }

  /// GET, где тело — JSON-массив (например история чеков).
  Future<List<dynamic>> getJsonList(String path, {bool auth = true}) async {
    final res = await _sendWithRefresh(
      () => _client.get(_uri(path), headers: _headers(auth: auth)),
      auth: auth,
    );
    return _decodeList(res);
  }

  Future<Map<String, dynamic>> postJson(
    String path,
    Object body, {
    bool auth = true,
  }) async {
    final res = await _sendWithRefresh(
      () => _client.post(_uri(path), headers: _headers(auth: auth), body: jsonEncode(body)),
      auth: auth,
    );
    return _decode(res);
  }

  /// Multipart-загрузка (Фаза C — фото чека). file/qr опциональны.
  Future<Map<String, dynamic>> postMultipart(
    String path, {
    Map<String, String> fields = const {},
    List<int>? fileBytes,
    String? fileField,
    String? fileName,
    String? contentTypeSub,
  }) async {
    Future<http.Response> build() async {
      final req = http.MultipartRequest('POST', _uri(path));
      req.headers.addAll(_headers(auth: true, json: false));
      req.fields.addAll(fields);
      if (fileBytes != null && fileField != null) {
        req.files.add(http.MultipartFile.fromBytes(fileField, fileBytes, filename: fileName ?? 'receipt.jpg'));
      }
      final streamed = await _client.send(req);
      return http.Response.fromStream(streamed);
    }

    final res = await _sendWithRefresh(build, auth: true);
    return _decode(res);
  }

  // ── Внутреннее ─────────────────────────────────────────────────────────────

  Uri _uri(String path) => Uri.parse('$_baseUrl$path');

  Map<String, String> _headers({required bool auth, bool json = true}) {
    final h = <String, String>{};
    if (json) h['Content-Type'] = 'application/json';
    if (auth && _tokenStore.hasTokens) {
      h['Authorization'] = 'Bearer ${_tokenStore.tokens!.accessToken}';
    }
    return h;
  }

  /// Выполняет запрос; на 401 (для авторизованных) — refresh + один повтор.
  /// Повтор пересоздаёт запрос через тот же thunk, поэтому подхватывает свежий токен.
  Future<http.Response> _sendWithRefresh(
    Future<http.Response> Function() request, {
    required bool auth,
  }) async {
    http.Response res;
    try {
      res = await request();
    } catch (_) {
      throw const ApiException.network();
    }
    if (res.statusCode == 401 && auth && _tokenStore.hasTokens) {
      final refreshed = await _tryRefresh();
      if (refreshed) {
        try {
          res = await request();
        } catch (_) {
          throw const ApiException.network();
        }
      }
    }
    return res;
  }

  Future<bool> _tryRefresh() async {
    final tokens = _tokenStore.tokens;
    if (tokens == null) return false;
    try {
      final res = await _client.post(
        _uri('/api/mobile/auth/refresh'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'refreshToken': tokens.refreshToken}),
      );
      if (res.statusCode == 200) {
        final json = jsonDecode(res.body) as Map<String, dynamic>;
        _tokenStore.save(AuthTokens.fromJson(json['tokens'] as Map<String, dynamic>));
        return true;
      }
    } catch (_) {
      // fallthrough → clear
    }
    // Refresh не удался — сессия мертва.
    _tokenStore.clear();
    return false;
  }

  Map<String, dynamic> _decode(http.Response res) {
    final ok = res.statusCode >= 200 && res.statusCode < 300;
    final bodyText = utf8.decode(res.bodyBytes);
    final dynamic parsed = bodyText.isEmpty ? <String, dynamic>{} : jsonDecode(bodyText);

    if (ok) {
      return parsed is Map<String, dynamic> ? parsed : <String, dynamic>{};
    }
    // Ошибка — пытаемся достать машинный код и сообщение бэкенда.
    if (parsed is Map<String, dynamic>) {
      throw ApiException(
        message: (parsed['message'] as String?) ?? 'Ошибка сервера',
        code: parsed['code'] as String?,
        statusCode: res.statusCode,
      );
    }
    throw ApiException(message: 'Ошибка сервера', statusCode: res.statusCode);
  }

  List<dynamic> _decodeList(http.Response res) {
    final ok = res.statusCode >= 200 && res.statusCode < 300;
    final bodyText = utf8.decode(res.bodyBytes);
    final dynamic parsed = bodyText.isEmpty ? <dynamic>[] : jsonDecode(bodyText);
    if (ok) return parsed is List ? parsed : <dynamic>[];
    // Ошибка приходит объектом {code,message}.
    if (parsed is Map<String, dynamic>) {
      throw ApiException(
        message: (parsed['message'] as String?) ?? 'Ошибка сервера',
        code: parsed['code'] as String?,
        statusCode: res.statusCode,
      );
    }
    throw ApiException(message: 'Ошибка сервера', statusCode: res.statusCode);
  }
}

final apiClientProvider = Provider<ApiClient>(
  (ref) => ApiClient(ref.read(tokenStoreProvider)),
);
