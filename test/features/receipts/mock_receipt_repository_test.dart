import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/receipts/data/mock_receipt_repository.dart';
import 'package:pharmacy/features/receipts/data/receipt_repository.dart';

void main() {
  test('submitReceipt добавляет чек inReview в начало истории', () async {
    final repo = MockReceiptRepository();
    final before = (await repo.loadReceipts()).length;

    final r = await repo.submitReceipt(title: 'Аквамарис', pharmacyName: 'Аптека №1');
    expect(r.status, ReceiptStatus.inReview);
    expect(r.title, 'Аквамарис');
    expect(r.pharmacy, 'Аптека №1');

    final after = await repo.loadReceipts();
    expect(after.length, before + 1);
    expect(after.first.title, 'Аквамарис');
  });

  test('submitReceipt шлёт сигнал в changes', () async {
    final repo = MockReceiptRepository();
    final fired = expectLater(repo.changes, emits(anything));
    await repo.submitReceipt(title: 'Тест');
    await fired;
  });
}
