/// Промо-товар (акция) из ленты `/api/mobile/promotions`: товар витрины Medusa +
/// поля акции, заданные админом (ценовые пороги с бонусом + диапазон дат).
class Promotion {
  const Promotion({
    required this.id,
    required this.productId,
    required this.name,
    this.brand,
    this.mnn,
    this.rxOtc,
    this.imageUrl,
    this.barcode,
    this.category,
    this.categories = const [],
    this.dateStart,
    this.dateEnd,
    this.tiers = const [],
  });

  /// id промо-кампании (pr_*) — его шлём с чеком при выборе акции.
  final String id;

  /// id товара Medusa (prod_*) — для детали товара / сверки.
  final String productId;
  final String name;
  final String? brand;
  final String? mnn;
  final String? rxOtc;
  final String? imageUrl;
  final String? barcode;
  final String? category;
  final List<String> categories;
  final DateTime? dateStart;
  final DateTime? dateEnd;
  final List<PromoTier> tiers;

  bool get isRx => (rxOtc ?? '').toLowerCase() == 'rx';

  /// Подпись диапазона дат: «с 1 — 30 июня» / «с 28 мая — 3 июня».
  String? get dateLabel {
    final s = dateStart, e = dateEnd;
    if (s == null && e == null) return null;
    if (s != null && e != null) {
      return s.month == e.month
          ? 'с ${s.day} — ${e.day} ${_month(e.month)}'
          : 'с ${s.day} ${_month(s.month)} — ${e.day} ${_month(e.month)}';
    }
    final d = (s ?? e)!;
    return '${s != null ? 'с' : 'до'} ${d.day} ${_month(d.month)}';
  }

  factory Promotion.fromJson(Map<String, dynamic> j) => Promotion(
        id: j['id'] as String? ?? '',
        productId: j['productId'] as String? ?? '',
        name: j['name'] as String? ?? 'Без названия',
        brand: j['brand'] as String?,
        mnn: j['mnn'] as String?,
        rxOtc: j['rxOtc'] as String?,
        imageUrl: j['imageUrl'] as String?,
        barcode: j['barcode'] as String?,
        category: j['category'] as String?,
        categories:
            ((j['categories'] as List?) ?? const []).whereType<String>().toList(),
        dateStart: _date(j['dateStart']),
        dateEnd: _date(j['dateEnd']),
        tiers: ((j['tiers'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => PromoTier.fromJson(m.cast<String, dynamic>()))
            .toList(),
      );
}

/// Ценовой порог акции: «от [minQty] шт — [price]₸» (+ разовый [bonus]₸ за порог).
class PromoTier {
  const PromoTier({required this.minQty, required this.price, this.bonus = 0});

  final int minQty;
  final int price;
  final int bonus;

  factory PromoTier.fromJson(Map<String, dynamic> j) => PromoTier(
        minQty: (j['minQty'] as num?)?.toInt() ?? 0,
        price: (j['price'] as num?)?.toInt() ?? 0,
        bonus: (j['bonus'] as num?)?.toInt() ?? 0,
      );
}

DateTime? _date(dynamic v) =>
    v is String && v.isNotEmpty ? DateTime.tryParse(v) : null;

const _months = [
  '', 'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря',
];

String _month(int m) => (m >= 1 && m <= 12) ? _months[m] : '';
