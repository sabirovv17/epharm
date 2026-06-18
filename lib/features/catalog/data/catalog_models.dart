/// Модели реального каталога товаров (приходит из бэкенда `/api/mobile/catalog/*`,
/// который проксирует Medusa-витрину). Все «обогащаемые» поля nullable: каталог
/// наполняется постепенно (часть товаров пока без цены/фото/бренда).
library;

class CatalogProduct {
  const CatalogProduct({
    required this.id,
    required this.name,
    this.brand,
    this.mnn,
    this.rxOtc,
    this.price,
    this.currency = 'KZT',
    this.imageUrl,
    this.barcode,
    this.category,
    this.categories = const [],
  });

  final String id;
  final String name;
  final String? brand;
  final String? mnn;
  final String? rxOtc; // "OTC" | "Rx"
  final int? price; // в тенге; null → «уточняйте в аптеке»
  final String currency;
  final String? imageUrl;
  final String? barcode;
  final String? category;

  /// Все категории товара — для клиентского фильтра в ленте Home.
  final List<String> categories;

  bool get isRx => (rxOtc ?? '').toLowerCase() == 'rx';

  factory CatalogProduct.fromJson(Map<String, dynamic> j) => CatalogProduct(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '',
        brand: j['brand'] as String?,
        mnn: j['mnn'] as String?,
        rxOtc: j['rxOtc'] as String?,
        price: (j['price'] as num?)?.toInt(),
        currency: j['currency'] as String? ?? 'KZT',
        imageUrl: j['imageUrl'] as String?,
        barcode: j['barcode'] as String?,
        category: j['category'] as String?,
        categories:
            ((j['categories'] as List?) ?? const []).whereType<String>().toList(),
      );
}

class CatalogPage {
  const CatalogPage({
    required this.items,
    required this.total,
    required this.limit,
    required this.offset,
  });

  final List<CatalogProduct> items;
  final int total;
  final int limit;
  final int offset;

  factory CatalogPage.fromJson(Map<String, dynamic> j) => CatalogPage(
        items: ((j['items'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogProduct.fromJson)
            .toList(),
        total: (j['total'] as num?)?.toInt() ?? 0,
        limit: (j['limit'] as num?)?.toInt() ?? 0,
        offset: (j['offset'] as num?)?.toInt() ?? 0,
      );
}

class CatalogMarketplaceLink {
  const CatalogMarketplaceLink({required this.platform, this.url, this.price});

  final String platform; // kaspi | wb | ozon | …
  final String? url;
  final int? price;

  factory CatalogMarketplaceLink.fromJson(Map<String, dynamic> j) =>
      CatalogMarketplaceLink(
        platform: j['platform'] as String? ?? '',
        url: j['url'] as String?,
        price: (j['price'] as num?)?.toInt(),
      );
}

/// Категория каталога (Medusa) с иерархией: `parentId` указывает на родителя
/// (null — корневая). Используется для построения дерева в фильтре категорий.
class MobileCategory {
  const MobileCategory({
    required this.id,
    required this.name,
    this.parentId,
  });

  final String id;
  final String name;
  final String? parentId;

  factory MobileCategory.fromJson(Map<String, dynamic> j) => MobileCategory(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '',
        parentId: j['parentId'] as String?,
      );
}

/// Вопрос-ответ из ПИМ (Medusa metadata.faq).
class CatalogQaItem {
  const CatalogQaItem({required this.q, required this.a});

  final String q;
  final String a;

  factory CatalogQaItem.fromJson(Map<String, dynamic> j) => CatalogQaItem(
        q: j['q'] as String? ?? '',
        a: j['a'] as String? ?? '',
      );
}

class CatalogProductDetail {
  const CatalogProductDetail({
    required this.id,
    required this.name,
    this.brand,
    this.mnn,
    this.atc,
    this.rxOtc,
    this.price,
    this.currency = 'KZT',
    this.imageUrl,
    this.images = const [],
    this.barcode,
    this.category,
    this.country,
    this.manufacturer,
    this.description,
    this.keyFacts = const [],
    this.marketplaceLinks = const [],
    this.qa = const [],
    this.hasActiveCampaign = false,
    this.bonus,
    this.promoId,
    this.campaignTitle,
  });

  final String id;
  final String name;
  final String? brand;
  final String? mnn;
  final String? atc;
  final String? rxOtc;
  final int? price;
  final String currency;
  final String? imageUrl;
  final List<String> images;
  final String? barcode;
  final String? category;
  final String? country;
  final String? manufacturer;
  final String? description;
  final List<String> keyFacts;
  final List<CatalogMarketplaceLink> marketplaceLinks;
  final List<CatalogQaItem> qa;

  /// У товара есть активная кампания (промо). Не зависит от авторизации.
  final bool hasActiveCampaign;

  /// Бонус фармацевту за продажу по активной кампании, ₸. Приходит ТОЛЬКО
  /// авторизованному (аноним → null). null → блок «Получить бонус» не рисуем.
  final int? bonus;

  /// Id акции (`pr_*`) — пробрасываем в чек как заявленную акцию. null — нет кампании.
  final String? promoId;

  /// Заголовок кампании (для подписи блока бонуса). null — нет кампании.
  final String? campaignTitle;

  bool get isRx => (rxOtc ?? '').toLowerCase() == 'rx';

  factory CatalogProductDetail.fromJson(Map<String, dynamic> j) =>
      CatalogProductDetail(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '',
        brand: j['brand'] as String?,
        mnn: j['mnn'] as String?,
        atc: j['atc'] as String?,
        rxOtc: j['rxOtc'] as String?,
        price: (j['price'] as num?)?.toInt(),
        currency: j['currency'] as String? ?? 'KZT',
        imageUrl: j['imageUrl'] as String?,
        images:
            ((j['images'] as List?) ?? const []).whereType<String>().toList(),
        barcode: j['barcode'] as String?,
        category: j['category'] as String?,
        country: j['country'] as String?,
        manufacturer: j['manufacturer'] as String?,
        description: j['description'] as String?,
        keyFacts:
            ((j['keyFacts'] as List?) ?? const []).whereType<String>().toList(),
        marketplaceLinks: ((j['marketplaceLinks'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogMarketplaceLink.fromJson)
            .toList(),
        qa: ((j['qa'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogQaItem.fromJson)
            .toList(),
        hasActiveCampaign: j['hasActiveCampaign'] as bool? ?? false,
        bonus: (j['bonus'] as num?)?.toInt(),
        promoId: j['promoId'] as String?,
        campaignTitle: j['campaignTitle'] as String?,
      );
}

/// Одна рекомендация в карточке товара (ДОП.3b): товар-рекомендация + подсказка + бонус.
class CatalogRecommendation {
  const CatalogRecommendation({
    required this.product,
    this.note,
    this.bonus,
    this.hasActiveCampaign = false,
    this.group = 'alternative',
  });

  final CatalogProduct product;

  /// Короткий скрипт/подсказка фармацевту (из правила); null — нет текста.
  final String? note;

  /// Бонус фармацевту за рекомендацию, ₸; null — не показываем бейдж.
  final int? bonus;

  /// У рекомендованного товара есть активная кампания (промо).
  final bool hasActiveCampaign;

  /// Группа рекомендации (определяет секцию в карточке):
  ///  - `alternative` — замена;
  ///  - `crosssell_with_campaign` — допродажа на промоции (кликабельна, с бонусом);
  ///  - `crosssell_no_campaign` — сопутствующий товар (инфо, без бонуса).
  final String group;

  factory CatalogRecommendation.fromJson(Map<String, dynamic> j) =>
      CatalogRecommendation(
        product: CatalogProduct.fromJson(
          ((j['product'] as Map?)?.cast<String, dynamic>()) ?? const {},
        ),
        note: j['note'] as String?,
        bonus: (j['bonus'] as num?)?.toInt(),
        hasActiveCampaign: j['hasActiveCampaign'] as bool? ?? false,
        group: j['group'] as String? ?? 'alternative',
      );
}

/// Рекомендации к товару карточки (ДОП.3b): «Альтернативы» (замены) и «Дополнения» (кросс-селл).
/// Пусто → секции в карточке не рисуются.
class CatalogRecommendations {
  const CatalogRecommendations({
    this.alternatives = const [],
    this.crosssells = const [],
  });

  final List<CatalogRecommendation> alternatives;
  final List<CatalogRecommendation> crosssells;

  bool get isEmpty => alternatives.isEmpty && crosssells.isEmpty;

  static const empty = CatalogRecommendations();

  factory CatalogRecommendations.fromJson(Map<String, dynamic> j) =>
      CatalogRecommendations(
        alternatives: ((j['alternatives'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogRecommendation.fromJson)
            .toList(),
        crosssells: ((j['crosssells'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogRecommendation.fromJson)
            .toList(),
      );
}

/// Глобальные пулы рекомендаций для пилюль ленты каталога:
///  - alternatives — «Альтернативы» (всё, что продвигаем как замены);
///  - crosssells   — «Дополнения» (всё, что продвигаем как кросс-селл).
/// В отличие от [CatalogRecommendations] — не привязаны к товару карточки, а собирают
/// весь ассортимент замен/допов из правил кампаний (для просмотра отдельным разделом).
class CatalogRecommendationPools {
  const CatalogRecommendationPools({
    this.alternatives = const [],
    this.crosssells = const [],
  });

  final List<CatalogProduct> alternatives;
  final List<CatalogProduct> crosssells;

  bool get isEmpty => alternatives.isEmpty && crosssells.isEmpty;

  static const empty = CatalogRecommendationPools();

  factory CatalogRecommendationPools.fromJson(Map<String, dynamic> j) =>
      CatalogRecommendationPools(
        alternatives: ((j['alternatives'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogProduct.fromJson)
            .toList(),
        crosssells: ((j['crosssells'] as List?) ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(CatalogProduct.fromJson)
            .toList(),
      );
}

/// «1 990 ₸» с разрядкой пробелами, либо «Цена в аптеке» если цена не задана.
String catalogPriceLabel(int? price, {String currency = 'KZT'}) {
  if (price == null) return 'Цена в аптеке';
  final s = price.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write(' ');
    buf.write(s[i]);
  }
  final symbol = currency.toUpperCase() == 'KZT' ? '₸' : currency.toUpperCase();
  return '$buf $symbol';
}
