import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pharmacy/core/network/api_client.dart';
import 'package:pharmacy/core/network/token_store.dart';
import 'package:pharmacy/features/receipts/data/api_receipt_repository.dart';
import 'package:pharmacy/features/receipts/data/receipt_repository.dart';

ApiReceiptRepository _repo(MockClient client) => ApiReceiptRepository(
      ApiClient(
        TokenStore()..save(const AuthTokens(accessToken: 'a', refreshToken: 'r')),
        baseUrl: 'http://t',
        client: client,
      ),
    );

void main() {
  test('loadReceipts маппит массив и статусы', () async {
    final repo = _repo(MockClient((req) async {
      expect(req.method, 'GET');
      expect(req.url.path, '/api/mobile/receipts');
      expect(req.headers['Authorization'], 'Bearer a');
      return http.Response(
        jsonEncode([
          {'id': '1', 'status': 'confirmed', 'productName': 'Аквамарис', 'sku': 'p_a', 'amount': 1500, 'createdAt': '2026-06-09T10:00:00Z', 'pharmacyName': 'Аптека'},
          {'id': '2', 'status': 'rejected', 'productName': 'X', 'sku': 'p_x', 'amount': 0, 'rejectedReason': 'дубль', 'createdAt': '2026-06-09T09:00:00Z', 'pharmacyName': 'Аптека'},
          {'id': '3', 'status': 'inReview', 'productName': 'Y', 'sku': 'p_y', 'amount': 0, 'createdAt': '2026-06-09T08:00:00Z', 'pharmacyName': 'Аптека'},
        ]),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    }));
    final list = await repo.loadReceipts();
    expect(list.length, 3);
    expect(list[0].status, ReceiptStatus.confirmed);
    expect(list[0].title, 'Аквамарис');
    expect(list[0].amountKzt, 1500);
    expect(list[1].status, ReceiptStatus.rejected);
    expect(list[1].rejectedReason, 'дубль');
    expect(list[2].status, ReceiptStatus.inReview);
  });

  test('submitReceipt шлёт multipart только с фото и маппит ответ (ДОП.8)', () async {
    final tmp = await File('${Directory.systemTemp.path}/epharm_r_test.jpg').writeAsBytes([1, 2, 3, 4]);
    final repo = _repo(MockClient((req) async {
      expect(req.method, 'POST');
      expect(req.url.path, '/api/mobile/receipts');
      expect(req.headers['content-type'], contains('multipart/form-data'));
      // ДОП.8: аптека/акции больше НЕ передаются с клиента — только файл.
      expect(req.body, isNot(contains('pharmacyId')));
      expect(req.body, isNot(contains('promoIds')));
      expect(req.body, contains('filename="receipt.jpg"'));
      return http.Response(
        jsonEncode({'id': 'rcp_1', 'status': 'inReview', 'productName': 'Аквамарис', 'sku': 'p_a', 'amount': 0, 'bonus': 380, 'bonusCredited': 0, 'photoUrl': 'http://t/p.jpg', 'createdAt': '2026-06-09T10:00:00Z', 'pharmacyName': 'Аптека на Абая 10'}),
        201,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    }));
    final r = await repo.submitReceipt(title: 'Чек', photoPath: tmp.path);
    expect(r.id, 'rcp_1');
    expect(r.status, ReceiptStatus.inReview);
    // pharmacyName из ответа сервера (аптека из профиля) всё так же маппится в r.pharmacy.
    expect(r.pharmacy, 'Аптека на Абая 10');
    expect(r.bonus, 380);
    expect(r.photoUrl, 'http://t/p.jpg');
    await tmp.delete();
  });

  test('submitReceipt с promoIds шлёт CSV-поле promoIds', () async {
    final tmp = await File('${Directory.systemTemp.path}/epharm_r_promo.jpg')
        .writeAsBytes([1, 2, 3, 4]);
    final repo = _repo(MockClient((req) async {
      expect(req.method, 'POST');
      expect(req.headers['content-type'], contains('multipart/form-data'));
      // Заявленные акции уходят CSV-полем promoIds (подсказка для бэка).
      expect(req.body, contains('name="promoIds"'));
      expect(req.body, contains('pr_1,pr_2'));
      return http.Response(
        jsonEncode({'id': 'rcp_2', 'status': 'inReview', 'productName': 'X', 'sku': 'p_x', 'amount': 0, 'createdAt': '2026-06-09T10:00:00Z', 'pharmacyName': 'Аптека'}),
        201,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    }));
    final r = await repo.submitReceipt(
      title: 'Чек',
      photoPath: tmp.path,
      promoIds: const ['pr_1', 'pr_2'],
    );
    expect(r.id, 'rcp_2');
    await tmp.delete();
  });

  test('submitReceipt с пустым promoIds НЕ шлёт поле promoIds', () async {
    final tmp = await File('${Directory.systemTemp.path}/epharm_r_empty.jpg')
        .writeAsBytes([1, 2, 3, 4]);
    final repo = _repo(MockClient((req) async {
      expect(req.body, isNot(contains('promoIds')));
      return http.Response(
        jsonEncode({'id': 'rcp_3', 'status': 'inReview', 'productName': 'X', 'sku': 'p_x', 'amount': 0, 'createdAt': '2026-06-09T10:00:00Z', 'pharmacyName': 'Аптека'}),
        201,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    }));
    await repo.submitReceipt(title: 'Чек', photoPath: tmp.path, promoIds: const []);
    await tmp.delete();
  });

  test('ошибка сервера → ApiException пробрасывается', () async {
    final repo = _repo(MockClient((req) async => http.Response(
          jsonEncode({'code': 'VALIDATION_FAILED', 'message': 'Нужно фото чека'}),
          400,
          headers: {'content-type': 'application/json; charset=utf-8'},
        )));
    await expectLater(repo.submitReceipt(title: 'X'), throwsA(isA<Object>()));
  });
}
