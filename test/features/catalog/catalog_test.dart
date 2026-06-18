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

  group('CatalogProductDetail.fromJson — поля кампании (п.2)', () {
    test('парсит hasActiveCampaign/bonus/promoId/campaignTitle', () {
      final d = CatalogProductDetail.fromJson(const {
        'id': 'p1',
        'name': 'Larimide Lifting',
        'price': 12000,
        'hasActiveCampaign': true,
        'bonus': 520,
        'promoId': 'pr_42',
        'campaignTitle': 'Весенняя акция',
      });
      expect(d.hasActiveCampaign, isTrue);
      expect(d.bonus, 520);
      expect(d.promoId, 'pr_42');
      expect(d.campaignTitle, 'Весенняя акция');
    });

    test('аноним/без кампании → дефолты (bonus/promoId null, hasActiveCampaign false)',
        () {
      final d = CatalogProductDetail.fromJson(const {
        'id': 'p2',
        'name': 'Везилют',
      });
      expect(d.hasActiveCampaign, isFalse);
      expect(d.bonus, isNull);
      expect(d.promoId, isNull);
      expect(d.campaignTitle, isNull);
    });
  });

  group('CatalogRecommendation.fromJson — group/hasActiveCampaign (п.7)', () {
    test('парсит group и hasActiveCampaign', () {
      final r = CatalogRecommendation.fromJson(const {
        'product': {'id': 'p1', 'name': 'Допродажа'},
        'bonus': 300,
        'hasActiveCampaign': true,
        'group': 'crosssell_with_campaign',
      });
      expect(r.group, 'crosssell_with_campaign');
      expect(r.hasActiveCampaign, isTrue);
      expect(r.bonus, 300);
      expect(r.product.id, 'p1');
    });

    test('дефолты: group=="alternative", hasActiveCampaign=false', () {
      final r = CatalogRecommendation.fromJson(const {
        'product': {'id': 'p2', 'name': 'Замена'},
      });
      expect(r.group, 'alternative');
      expect(r.hasActiveCampaign, isFalse);
    });

    test('crosssells разбиваются на 2 группы по полю group', () {
      final recs = CatalogRecommendations.fromJson(const {
        'alternatives': [
          {
            'product': {'id': 'a1', 'name': 'Альт 1'},
            'group': 'alternative',
          },
        ],
        'crosssells': [
          {
            'product': {'id': 'c1', 'name': 'Промо-доп'},
            'group': 'crosssell_with_campaign',
            'bonus': 400,
            'hasActiveCampaign': true,
          },
          {
            'product': {'id': 'c2', 'name': 'Сопутствующий'},
            'group': 'crosssell_no_campaign',
          },
        ],
      });
      final upsell = recs.crosssells
          .where((r) => r.group == 'crosssell_with_campaign')
          .toList();
      final companions = recs.crosssells
          .where((r) => r.group == 'crosssell_no_campaign')
          .toList();
      expect(upsell.length, 1);
      expect(upsell.first.product.id, 'c1');
      expect(upsell.first.bonus, 400);
      expect(companions.length, 1);
      expect(companions.first.product.id, 'c2');
      expect(companions.first.bonus, isNull);
      expect(recs.alternatives.length, 1);
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
