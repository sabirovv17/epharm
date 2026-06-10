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
