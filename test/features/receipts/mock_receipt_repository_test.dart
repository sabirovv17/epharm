import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/receipts/data/mock_receipt_repository.dart';
import 'package:pharmacy/features/receipts/data/receipt_repository.dart';

void main() {
  test('submitReceipt добавляет чек inReview в начало истории', () async {
    final repo = MockReceiptRepository();
    final before = (await repo.loadReceipts()).length;

    // ДОП.8: только фото/title — аптеку/акции определяет сервер.
    final r = await repo.submitReceipt(title: 'Аквамарис');
    expect(r.status, ReceiptStatus.inReview);
    expect(r.title, 'Аквамарис');
    expect(r.pharmacy, isNull);

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
