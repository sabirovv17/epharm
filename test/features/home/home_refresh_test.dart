import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';
import 'package:pharmacy/features/home/data/home_repository.dart' show Promo;
import 'package:pharmacy/features/home/presentation/home_screen.dart';
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';

/// Живая синхронизация с админкой: pull-to-refresh / авто-рефреш должны
/// ПЕРЕЗАПРАШИВАТЬ ленту акций из backend (а не показывать закэшированную),
/// чтобы заведённая в админке акция появлялась без перезапуска приложения.

const _a = Promotion(id: 'pr_a', productId: 'prod_a', name: 'Аквамарис', brand: 'Stada');
const _b = Promotion(id: 'pr_b', productId: 'prod_b', name: 'Новая акция', brand: 'Inkar');

void main() {
  testWidgets('refreshHomeData перезапрашивает ленту акций (видит новую акцию)', (
    tester,
  ) async {
    var calls = 0;
    // 1-й вызов отдаёт одну акцию, после refresh — две (имитируем добавление в админке).
    final container = ProviderContainer(
      overrides: [
        promotionsProvider.overrideWith((ref) async {
          calls++;
          return calls == 1 ? const [_a] : const [_a, _b];
        }),
        promoListProvider.overrideWith((ref) async => const <Promo>[]),
      ],
    );
    addTearDown(container.dispose);

    late WidgetRef capturedRef;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Consumer(
            builder: (ctx, ref, _) {
              capturedRef = ref;
              // Подписываемся, чтобы провайдер проинициализировался (1-й вызов).
              ref.watch(promotionsProvider);
              return const SizedBox.shrink();
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(calls, 1, reason: 'первый раз лента загрузилась');
    expect(container.read(promotionsProvider).value, const [_a]);

    // Pull-to-refresh / авто-рефреш → должен сходить за свежими данными.
    // ref.refresh(promotionsProvider.future) инвалидирует провайдер и возвращает
    // НОВЫЙ future; await держит до прихода свежих данных (а не отдаёт кэш).
    await refreshHomeData(capturedRef);
    await tester.pumpAndSettle();

    expect(calls, 2, reason: 'refresh перезапросил ленту, а не отдал кэш');
    expect(
      container.read(promotionsProvider).value,
      const [_a, _b],
      reason: 'новая акция из админки видна после refresh',
    );
  });

  testWidgets('на главной есть RefreshIndicator (жест pull-to-refresh)', (
    tester,
  ) async {
    final container = ProviderContainer(
      overrides: [
        promotionsProvider.overrideWith((ref) async => const [_a]),
        promoListProvider.overrideWith((ref) async => const <Promo>[]),
      ],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: HomeScreen()),
      ),
    );
    await tester.pumpAndSettle();

    // Лента главной обёрнута в RefreshIndicator → жест «потянуть вниз» доступен.
    expect(find.byType(RefreshIndicator), findsOneWidget);
  });
}
