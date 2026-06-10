import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/catalog/data/catalog_models.dart';
import 'package:pharmacy/features/catalog/data/mock_catalog_repository.dart';

void main() {
  group('CatalogPage.fromJson', () {
    test('маппит товары и переживает отсутствующие поля', () {
      final page = CatalogPage.fromJson(const {
        'items': [
          {
            'id': 'p1',
            'name': 'Аспирин Кардио',
            'brand': 'Bayer',
            'rxOtc': 'OTC',
            'price': 1290,
            'currency': 'KZT',
          },
          {'id': 'p2', 'name': 'Везилют'}, // без цены/бренда — каталог в наполнении
        ],
        'total': 77,
        'limit': 24,
        'offset': 0,
      });
      expect(page.items.length, 2);
      expect(page.total, 77);
      expect(page.items[0].brand, 'Bayer');
      expect(page.items[0].price, 1290);
      expect(page.items[1].brand, isNull);
      expect(page.items[1].price, isNull);
      expect(page.items[1].currency, 'KZT'); // дефолт
    });

    test('пустой/битый JSON → пустая страница без падения', () {
      final page = CatalogPage.fromJson(const {});
      expect(page.items, isEmpty);
      expect(page.total, 0);
    });
  });

  group('catalogPriceLabel', () {
    test('разрядка пробелами или «Цена в аптеке»', () {
      expect(catalogPriceLabel(1290), '1 290 ₸');
      expect(catalogPriceLabel(990), '990 ₸');
      expect(catalogPriceLabel(1234567), '1 234 567 ₸');
      expect(catalogPriceLabel(null), 'Цена в аптеке');
    });
  });

  group('CatalogProduct.isRx', () {
    test('распознаёт рецептурность', () {
      expect(const CatalogProduct(id: '1', name: 'a', rxOtc: 'Rx').isRx, isTrue);
      expect(
          const CatalogProduct(id: '1', name: 'a', rxOtc: 'OTC').isRx, isFalse);
      expect(const CatalogProduct(id: '1', name: 'a').isRx, isFalse);
    });
  });

  group('MockCatalogRepository', () {
    final repo = MockCatalogRepository();

    test('поиск фильтрует по названию/бренду/МНН', () async {
      final page = await repo.search(q: 'ибупрофен');
      expect(page.items, isNotEmpty);
      expect(
        page.items.every((p) => '${p.name} ${p.brand} ${p.mnn}'
            .toLowerCase()
            .contains('ибупрофен')),
        isTrue,
      );
    });

    test('пагинация режет выборку по offset/limit', () async {
      final first = await repo.search(limit: 3, offset: 0);
      expect(first.items.length, 3);
      expect(first.total, greaterThan(3));
      final second = await repo.search(limit: 3, offset: 3);
      expect(second.items.first.id, isNot(first.items.first.id));
    });

    test('detail отдаёт карточку с описанием и фактами', () async {
      final d = await repo.detail('m_aspirin');
      expect(d.name, contains('Аспирин'));
      expect(d.description, isNotNull);
      expect(d.keyFacts, isNotEmpty);
    });
  });
}
