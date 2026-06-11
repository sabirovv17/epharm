import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/home/application/home_controller.dart' show CatalogSort;
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';

/// Лента Home = пул промо-акций, фильтруемый на клиенте. Проверяем чистую
/// функцию applyPromotionFilters + парсинг Promotion (товар + пороги + даты).

Promotion _p(String name, {String? brand, List<String> cats = const [], String? mnn}) =>
    Promotion(
      id: 'pr_$name',
      productId: 'prod_$name',
      name: name,
      brand: brand,
      mnn: mnn,
      categories: cats,
      tiers: const [PromoTier(minQty: 1, price: 500), PromoTier(minQty: 10, price: 600, bonus: 900)],
    );

void main() {
  group('applyPromotionFilters', () {
    final items = [
      _p('Аспирин', brand: 'Bayer', cats: ['Анальгетики'], mnn: 'ASA'),
      _p('Везилют', brand: 'Access', cats: ['БАДы']),
      _p('Нурофен', brand: 'Bayer', cats: ['Анальгетики', 'БАДы']),
    ];
    List<Promotion> run({
      Set<String> brands = const {},
      Set<String> cats = const {},
      String q = '',
      CatalogSort sort = CatalogSort.nameAsc,
    }) =>
        applyPromotionFilters(
            items: items, brands: brands, categories: cats, query: q, sort: sort);

    test('без фильтров — все, сортировка А-Я', () {
      expect(run().map((p) => p.name), ['Аспирин', 'Везилют', 'Нурофен']);
    });
    test('сортировка Я-А', () {
      expect(run(sort: CatalogSort.nameDesc).first.name, 'Нурофен');
    });
    test('фильтр по бренду', () {
      expect(run(brands: {'Bayer'}).map((p) => p.name).toSet(), {'Аспирин', 'Нурофен'});
    });
    test('фильтр по категории — товар проходит если хотя бы одна совпадает', () {
      expect(run(cats: {'БАДы'}).map((p) => p.name).toSet(), {'Везилют', 'Нурофен'});
    });
    test('поиск по названию / бренду / МНН (case-insensitive)', () {
      expect(run(q: 'asa').single.name, 'Аспирин'); // по mnn
      expect(run(q: 'access').single.name, 'Везилют'); // по бренду
      expect(run(q: 'нур').single.name, 'Нурофен'); // по названию
    });
  });

  group('Promotion.fromJson', () {
    test('парсит товар + пороги + даты + dateLabel', () {
      final p = Promotion.fromJson({
        'id': 'pr_1', 'productId': 'prod_1', 'name': 'Лифта', 'brand': 'Abdi', 'rxOtc': 'Rx',
        'imageUrl': 'http://img', 'categories': ['Урология'],
        'dateStart': '2026-06-01', 'dateEnd': '2026-06-30',
        'tiers': [
          {'minQty': 1, 'price': 500, 'bonus': 0},
          {'minQty': 10, 'price': 600, 'bonus': 900},
        ],
      });
      expect(p.id, 'pr_1');
      expect(p.productId, 'prod_1');
      expect(p.isRx, true);
      expect(p.tiers.length, 2);
      expect(p.tiers[1].minQty, 10);
      expect(p.tiers[1].bonus, 900);
      expect(p.dateLabel, 'с 1 — 30 июня');
    });

    test('пустой/неполный JSON — без падений', () {
      final p = Promotion.fromJson({'id': 'x', 'productId': 'y', 'name': 'N'});
      expect(p.tiers, isEmpty);
      expect(p.dateLabel, isNull);
      expect(p.brand, isNull);
    });
  });
}
