import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';

/// Пилюли «Альтернативы»/«Дополнения» (homeRecoPoolProvider/RecoPool) убраны —
/// лента Home теперь всегда показывает пул акций. Вместо режима-пилюль появилась
/// сортировка «По сумме бонуса» (CatalogSort.bonusDesc). Тесты на неё ниже.

Promotion _promo(String name, List<int> bonuses) => Promotion(
      id: 'pr_$name',
      productId: 'prod_$name',
      name: name,
      tiers: [
        for (final b in bonuses) PromoTier(minQty: 1, price: 100, bonus: b),
      ],
    );

void main() {
  group('CatalogSort — новая опция bonusDesc', () {
    test('label = «По сумме бонуса» и опция идёт последней', () {
      expect(CatalogSort.bonusDesc.label, 'По сумме бонуса');
      expect(CatalogSort.values.last, CatalogSort.bonusDesc);
    });
  });

  group('applyPromotionFilters — сортировка по убыванию бонуса', () {
    test('упорядочивает по убыванию МАКСИМАЛЬНОГО бонуса промо', () {
      // У каждого промо берётся максимальный бонус среди порогов.
      final items = [
        _promo('low', [100, 50]), // max 100
        _promo('high', [300]), // max 300
        _promo('mid', [200, 150]), // max 200
        _promo('zero', const []), // нет порогов → 0
      ];

      final sorted = applyPromotionFilters(
        items: items,
        brands: const {},
        categories: const {},
        query: '',
        sort: CatalogSort.bonusDesc,
      );

      expect(
        sorted.map((p) => p.name).toList(),
        ['high', 'mid', 'low', 'zero'],
      );
    });

    test('промо без порогов уходят в конец (бонус 0)', () {
      final items = [
        _promo('noTiers', const []),
        _promo('withBonus', [10]),
      ];

      final sorted = applyPromotionFilters(
        items: items,
        brands: const {},
        categories: const {},
        query: '',
        sort: CatalogSort.bonusDesc,
      );

      expect(sorted.first.name, 'withBonus');
      expect(sorted.last.name, 'noTiers');
    });
  });
}
