import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';
import 'package:pharmacy/features/receipts/application/receipts_controller.dart';
import 'package:pharmacy/features/receipts/presentation/promo_picker_screen.dart';

/// Пикер чека берёт пул из promotionsProvider (а не из мок-каталога) и сохраняет
/// выбор в receiptDraftProvider. imageUrl=null → плейсхолдер, без сетевых вызовов.
const _promos = <Promotion>[
  Promotion(id: 'pr_aqua', productId: 'prod_a', name: 'Аквамарис спрей', brand: 'Stada'),
  Promotion(id: 'pr_pank', productId: 'prod_p', name: 'Панкраген капсулы', brand: 'Цитамины'),
];

Future<ProviderContainer> _pump(WidgetTester tester, {List<Promotion> promos = _promos}) async {
  final container = ProviderContainer(
    overrides: [promotionsProvider.overrideWith((ref) => Future.value(promos))],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => Navigator.of(ctx).push(
                  MaterialPageRoute(builder: (_) => const PromoPickerScreen()),
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets('пикер рендерит акции из промо-пула', (tester) async {
    await _pump(tester);
    expect(find.text('Аквамарис спрей'), findsOneWidget);
    expect(find.text('Панкраген капсулы'), findsOneWidget);
    // Пока ничего не выбрано — CTA заблокирован с подписью «Выберите акции».
    expect(find.text('Выберите акции'), findsOneWidget);
  });

  testWidgets('выбор акции → CTA считает и setPromos сохраняет выбор', (tester) async {
    final container = await _pump(tester);

    // Тапаем «Добавить» на первой карточке.
    await tester.tap(find.text('Добавить').first);
    await tester.pumpAndSettle();
    expect(find.text('Добавить · 1'), findsOneWidget);

    // Подтверждаем — экран закрывается, выбор уходит в draft.
    await tester.tap(find.text('Добавить · 1'));
    await tester.pumpAndSettle();

    final picked = container.read(receiptDraftProvider).promos;
    expect(picked.length, 1);
    expect(picked.first.id, 'pr_aqua');
  });

  testWidgets('пустой пул → подсказка вместо карточек', (tester) async {
    await _pump(tester, promos: const []);
    expect(find.text('Акции появятся, когда менеджер их добавит'), findsOneWidget);
  });
}
