import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pharmacy/core/network/api_client.dart';
import 'package:pharmacy/core/network/api_exception.dart';
import 'package:pharmacy/core/network/token_store.dart';
import 'package:pharmacy/features/auth/data/api_auth_repository.dart';

http.Response _json(Object body, int status) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json; charset=utf-8'});

ApiAuthRepository _repo(TokenStore store, MockClient client) =>
    ApiAuthRepository(ApiClient(store, baseUrl: 'http://t', client: client), store);

void main() {
  test('requestOtp шлёт POST /sms/request без авторизации', () async {
    var hit = false;
    final repo = _repo(TokenStore(), MockClient((req) async {
      hit = true;
      expect(req.url.path, '/api/mobile/auth/sms/request');
      expect(req.headers['Authorization'], isNull);
      expect(jsonDecode(req.body), {'phone': '+77771002030'});
      return _json({'sent': true, 'devCode': '544544'}, 200);
    }));
    await repo.requestOtp(phone: '+77771002030');
    expect(hit, true);
  });

  test('verifyOtp registered=false → токены не сохраняются', () async {
    final store = TokenStore();
    final repo = _repo(store, MockClient((req) async {
      expect(req.url.path, '/api/mobile/auth/sms/verify');
      return _json({'registered': false, 'tokens': null, 'pharmacist': null}, 200);
    }));
    final r = await repo.verifyOtp(phone: '+77770001122', code: '544544');
    expect(r.registered, false);
    expect(r.user, isNull);
    expect(store.hasTokens, false);
  });

  test('verifyOtp registered=true → сохраняет токены и маппит профиль', () async {
    final store = TokenStore();
    final repo = _repo(store, MockClient((req) async => _json({
          'registered': true,
          'tokens': {'accessToken': 'a', 'refreshToken': 'r'},
          'pharmacist': {'name': 'Иван', 'phone': '+77000000001', 'iin': '990101000111', 'balance': 42000},
        }, 200)));
    final r = await repo.verifyOtp(phone: '+77000000001', code: '544544');
    expect(r.registered, true);
    expect(r.user!.fio, 'Иван');
    expect(r.user!.balanceKzt, 42000);
    expect(store.tokens!.accessToken, 'a');
  });

  test('register → токены + pending-профиль с нулевым балансом', () async {
    final store = TokenStore();
    final repo = _repo(store, MockClient((req) async {
      expect(req.url.path, '/api/mobile/auth/register');
      final body = jsonDecode(req.body) as Map<String, dynamic>;
      expect(body['fio'], 'Тест Тестов');
      return _json({
        'tokens': {'accessToken': 'a2', 'refreshToken': 'r2'},
        'pharmacist': {'name': 'Тест Тестов', 'phone': '+77771002030', 'iin': '990101777777', 'balance': 0},
      }, 200);
    }));
    final u = await repo.register(phone: '+77771002030', fio: 'Тест Тестов', iin: '990101777777');
    expect(u.balanceKzt, 0);
    expect(store.tokens!.accessToken, 'a2');
  });

  test('ошибка кода → ApiException пробрасывается', () async {
    final repo = _repo(TokenStore(), MockClient((req) async => _json({'code': 'OTP_INVALID', 'message': 'Неверный код'}, 400)));
    expect(
      () => repo.verifyOtp(phone: '+77770001122', code: '000000'),
      throwsA(isA<ApiException>().having((e) => e.code, 'code', 'OTP_INVALID')),
    );
  });
}
