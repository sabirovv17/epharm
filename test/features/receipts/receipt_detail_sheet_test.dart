import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/receipts/data/receipt_repository.dart';
import 'package:pharmacy/features/receipts/presentation/receipt_detail_sheet.dart';

void main() {
  Future<void> openSheet(WidgetTester tester, Receipt r) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => ElevatedButton(
              onPressed: () => showReceiptDetailSheet(context, r),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('деталь чека показывает выбранную аптеку (баг-фикс)', (tester) async {
    const r = Receipt(
      id: 'r1',
      title: 'Аквамарис',
      amountKzt: 1620,
      dateLabel: '09.06 · 10:00',
      status: ReceiptStatus.confirmed,
      pharmacy: 'Аптека на Абая 10',
      bonus: 520,
      bonusCredited: 520,
    );
    await openSheet(tester, r);

    // Главное: аптека отображается (раньше терялась).
    expect(find.text('Аптека на Абая 10'), findsOneWidget);
    expect(find.text('Аптека'), findsOneWidget);
    expect(find.text('Аквамарис'), findsOneWidget);
    // Зачисленный бонус виден.
    expect(find.text('Бонус зачислен'), findsOneWidget);
  });

  testWidgets('без аптеки показывает «Не указана»', (tester) async {
    const r = Receipt(
      id: 'r2',
      title: 'Чек',
      amountKzt: 0,
      dateLabel: '09.06 · 11:00',
      status: ReceiptStatus.inReview,
    );
    await openSheet(tester, r);
    expect(find.text('Не указана'), findsOneWidget);
  });

  testWidgets('отклонённый чек показывает причину', (tester) async {
    const r = Receipt(
      id: 'r3',
      title: 'Чек',
      amountKzt: 1000,
      dateLabel: '09.06 · 12:00',
      status: ReceiptStatus.rejected,
      rejectedReason: 'Чек уже использован',
      pharmacy: 'Аптека 1',
    );
    await openSheet(tester, r);
    expect(find.text('Причина отклонения'), findsOneWidget);
    expect(find.text('Чек уже использован'), findsOneWidget);
  });
}
