import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pharmacy/core/network/api_client.dart';
import 'package:pharmacy/core/network/api_exception.dart';
import 'package:pharmacy/core/network/token_store.dart';

http.Response _json(Object body, int status) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json; charset=utf-8'});

void main() {
  group('ApiClient', () {
    test('postJson возвращает map на 200', () async {
      final store = TokenStore();
      final client = ApiClient(
        store,
        baseUrl: 'http://t',
        client: MockClient((req) async {
          expect(req.url.path, '/x');
          expect(jsonDecode(req.body), {'a': 1});
          return _json({'ok': true}, 200);
        }),
      );
      final res = await client.postJson('/x', {'a': 1}, auth: false);
      expect(res['ok'], true);
    });

    test('ошибка с {code,message} → ApiException с кодом', () async {
      final client = ApiClient(
        TokenStore(),
        baseUrl: 'http://t',
        client: MockClient((req) async => _json({'code': 'OTP_INVALID', 'message': 'Неверный код'}, 400)),
      );
      expect(
        () => client.postJson('/v', {}, auth: false),
        throwsA(isA<ApiException>()
            .having((e) => e.code, 'code', 'OTP_INVALID')
            .having((e) => e.message, 'message', 'Неверный код')),
      );
    });

    test('401 на авторизованном запросе → refresh + повтор с новым токеном', () async {
      final store = TokenStore()..save(const AuthTokens(accessToken: 'old', refreshToken: 'r1'));
      var meCalls = 0;
      final client = ApiClient(
        store,
        baseUrl: 'http://t',
        client: MockClient((req) async {
          if (req.url.path == '/api/mobile/auth/refresh') {
            return _json({'tokens': {'accessToken': 'new', 'refreshToken': 'r2'}}, 200);
          }
          meCalls++;
          if (meCalls == 1) {
            expect(req.headers['Authorization'], 'Bearer old');
            return http.Response('', 401);
          }
          // Повтор уже с обновлённым токеном.
          expect(req.headers['Authorization'], 'Bearer new');
          return _json({'name': 'X'}, 200);
        }),
      );
      final res = await client.getJson('/me');
      expect(res['name'], 'X');
      expect(store.tokens!.accessToken, 'new');
      expect(meCalls, 2);
    });

    test('401 + неудачный refresh → токены очищены, 401 пробрасывается', () async {
      final store = TokenStore()..save(const AuthTokens(accessToken: 'old', refreshToken: 'bad'));
      final client = ApiClient(
        store,
        baseUrl: 'http://t',
        client: MockClient((req) async {
          if (req.url.path == '/api/mobile/auth/refresh') return http.Response('', 401);
          return _json({'message': 'Не авторизован'}, 401);
        }),
      );
      await expectLater(
        () => client.getJson('/me'),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 401)),
      );
      expect(store.hasTokens, false);
    });
  });
}
