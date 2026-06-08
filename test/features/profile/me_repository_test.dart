import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pharmacy/core/network/api_client.dart';
import 'package:pharmacy/core/network/token_store.dart';
import 'package:pharmacy/features/profile/data/me_repository.dart';

void main() {
  test('fetchMe берёт /api/mobile/me с Bearer и маппит в User', () async {
    final store = TokenStore()..save(const AuthTokens(accessToken: 'a', refreshToken: 'r'));
    final client = ApiClient(
      store,
      baseUrl: 'http://t',
      client: MockClient((req) async {
        expect(req.method, 'GET');
        expect(req.url.path, '/api/mobile/me');
        expect(req.headers['Authorization'], 'Bearer a');
        return http.Response(
          jsonEncode({
            'name': 'Иван Иванов',
            'phone': '+77000000001',
            'iin': '990101000111',
            'balance': 12345,
            'tier': 'Gold',
            'status': 'active',
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );
    final user = await ApiMeRepository(client).fetchMe();
    expect(user.fio, 'Иван Иванов');
    expect(user.phone, '+77000000001');
    expect(user.balanceKzt, 12345);
  });
}
