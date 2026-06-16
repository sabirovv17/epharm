import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';
import 'package:pharmacy/features/home/presentation/widgets/brand_sheet.dart';
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';
import 'package:pharmacy/features/promotions/presentation/promo_product_card.dart';

void main() {
  testWidgets('PromoGridCard: название + бонус основным зелёным', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          // Высота как у ячейки сетки: childAspectRatio 0.46 → height = width / 0.46
          // (180 / 0.46 ≈ 391). Фото 3:4 при width 180 = 240px, content — остаток.
          body: SizedBox(
            width: 180,
            height: 391,
            child: PromoGridCard(
              promo: Promotion(
                id: 'p',
                productId: 'pr',
                name: 'Аквамарис спрей',
                brand: 'NOW',
                tiers: [PromoTier(minQty: 1, price: 1000, bonus: 500)],
              ),
              onTap: _noop,
            ),
          ),
        ),
      ),
    );
    expect(find.text('Аквамарис спрей'), findsOneWidget);
    expect(find.text('NOW'), findsOneWidget);
    expect(find.text('Бонус 500 ₸'), findsOneWidget);
  });

  testWidgets('Фильтр брендов: «Сбросить» сразу чистит провайдер (баг-фикс)', (
    tester,
  ) async {
    // Лента пустая — нам важна только кнопка «Сбросить» (видна, т.к. выбор не пуст).
    final container = ProviderContainer(
      overrides: [
        promotionsProvider.overrideWith((ref) async => const <Promotion>[]),
      ],
    );
    addTearDown(container.dispose);
    container.read(selectedBrandsProvider.notifier).replace({'Stada'});

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Builder(
            builder: (ctx) => Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () => showBrandSheet(ctx),
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

    expect(container.read(selectedBrandsProvider), {'Stada'});

    // Тап «Сбросить» — должен очистить ИМЕННО провайдер (а не только локальный
    // выбор), чтобы фильтр не «оставался» при закрытии листа свайпом.
    await tester.tap(find.text('Сбросить'));
    await tester.pumpAndSettle();

    expect(container.read(selectedBrandsProvider), isEmpty);
  });
}

void _noop() {}
