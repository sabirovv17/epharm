import 'package:flutter/material.dart';

/// ─── Promo carousel models (3 kinds: info / huggies / kotex) ────────────────
sealed class Promo {
  const Promo({required this.id, required this.background});
  final String id;
  final LinearGradient background;
}

class InfoPromo extends Promo {
  const InfoPromo({
    required super.id,
    required super.background,
    required this.title,
    required this.subtitle,
    required this.note,
  });

  final String title;
  final String subtitle;
  final String note;
}

class HuggiesPromo extends Promo {
  const HuggiesPromo({
    required super.id,
    required super.background,
    required this.title,
    required this.subtitle,
    required this.period,
    required this.amount,
    required this.footer,
  });

  final String title;
  final String subtitle;
  final String period;
  final String amount;
  final String footer;
}

class KotexPromo extends Promo {
  const KotexPromo({
    required super.id,
    required super.background,
    required this.title,
    required this.subtitle,
    required this.period,
    required this.amount,
    required this.footer,
  });

  final String title;
  final String subtitle;
  final String period;
  final String amount;
  final String footer;
}

/// ─── Product catalog ────────────────────────────────────────────────────────

class Tier {
  const Tier({required this.qty, required this.label, required this.price});
  final int qty;
  final String label;
  final String price;
}

class ProductPackage {
  const ProductPackage({
    required this.background,
    required this.label,
    this.sub,
    this.maker,
    this.accent,
  });

  final LinearGradient background;
  final String label;
  final String? sub;
  final String? maker;
  final Color? accent;
}

class Product {
  const Product({
    required this.id,
    required this.brand,
    required this.name,
    required this.period,
    this.bought,
    this.tiers = const [],
    this.bonuses = const [],
    this.restrictions,
    required this.pkg,
    this.featured = false,
    this.isNew = false,
    this.contest = false,
  });

  final int id;
  final String brand;
  final String name;
  final String period;
  final int? bought;
  final List<Tier> tiers;
  final List<String> bonuses;
  final String? restrictions;
  final ProductPackage pkg;
  final bool featured;
  final bool isNew;
  final bool contest;
}

/// ─── Repository ─────────────────────────────────────────────────────────────

class HomeRepository {
  Future<List<Promo>> loadPromos() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return _promos;
  }

  Future<List<Product>> loadProducts() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return _products;
  }

  Future<List<String>> loadBrands() async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    return _brands;
  }

  Future<List<String>> loadSortOptions() async => _sortOptions;

  static const _promos = <Promo>[
    InfoPromo(
      id: 'p1',
      background: LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFFFAF6E4), Color(0xFFF2EEDA)],
      ),
      title: 'Важная информация',
      subtitle: 'Проведена плановая проверка транзакций за 2025 год.',
      note: 'Подробная информация доступна на сайте.',
    ),
    HuggiesPromo(
      id: 'p2',
      background: LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFFF2E2BA), Color(0xFFE2C28C)],
      ),
      title: 'Huggies трусик-жөргектері мен',
      subtitle: 'Kotex-пен бірге 50 000 тг кепілді',
      period: '01.04.2026 – 31.05.2026',
      amount: '50000₸',
      footer: 'Гарантированно 50 000 тг вместе',
    ),
    KotexPromo(
      id: 'p3',
      background: LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFFE94A4A), Color(0xFFC5292B)],
      ),
      title: 'Күн сайын',
      subtitle: '10 000 тг бонус',
      period: '01.04.2026 – 31.05.2026',
      amount: '10000₸',
      footer: 'На каждый день — с бонусом 10 000 тг',
    ),
  ];

  static const _products = <Product>[
    Product(
      id: 1,
      brand: 'AIGP',
      name: 'AIGP Лифта 10 мг №1 табл.',
      period: 'с 1–31 мая',
      bought: 0,
      tiers: [
        Tier(qty: 1, label: 'от 1 шт.', price: '500₸'),
        Tier(qty: 10, label: 'от 10 шт.', price: '600₸'),
        Tier(qty: 20, label: 'от 20 шт.', price: '700₸'),
      ],
      bonuses: [
        'Бонус при достижении 2 порога: 900 тг',
        'Бонус при достижении 3 порога: 1900 тг',
      ],
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF0E3C2A), Color(0xFF1A6E4A)],
        ),
        label: 'ЛИФТА 10 мг',
        sub: 'Тадалафил / Tadalafil',
        maker: 'ABDI IBRAHIM',
      ),
      featured: true,
      contest: true,
    ),
    Product(
      id: 2,
      brand: 'Natrol',
      name: 'Natrol Мелатонин 10 мг №60 быстрораст.',
      period: '500₸/уп',
      restrictions: 'Есть ограничения по городам и аптекам',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFF4F1ED), Color(0xFFE8DDC4)],
        ),
        label: 'NATROL',
        accent: Color(0xFF8030A8),
      ),
    ),
    Product(
      id: 3,
      brand: 'Polpharma Santo',
      name: 'Аквадетрим витамин Д3 10мл 15000МЕ/мл',
      period: '300₸/уп',
      restrictions: 'Есть ограничения по аптекам',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFDCEEFF), Color(0xFF6FB7F2)],
        ),
        label: 'АкваДетрим',
        accent: Color(0xFF1E70A8),
      ),
      isNew: true,
    ),
    Product(
      id: 4,
      brand: 'Polpharma Santo',
      name: 'Аквадетрим форте 2000 №30',
      period: '400₸/уп',
      restrictions: 'Есть ограничения по аптекам',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFEFE3FB), Color(0xFFC18FE2)],
        ),
        label: 'АкваДетрим Форте',
        accent: Color(0xFF7B2DB0),
      ),
      isNew: true,
    ),
    Product(
      id: 5,
      brand: 'Alpen Pharma',
      name: 'Аллестил капли для приема 1 мг/мл 20 мл',
      period: '250₸/уп',
      restrictions: 'Есть ограничения по аптекам',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFE4F5FA), Color(0xFF9CD8E2)],
        ),
        label: 'АЛЛЕСТИЛ',
        accent: Color(0xFF1F8DA0),
      ),
    ),
    Product(
      id: 6,
      brand: 'Haleon',
      name: 'Витрум Витамин С 500 мг №60',
      period: 'с 1–31 мая',
      restrictions: 'Новинка месяца',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFFFF1D4), Color(0xFFFFCB52)],
        ),
        label: 'VITRUM C',
        accent: Color(0xFFB26B0D),
      ),
      isNew: true,
    ),
    Product(
      id: 7,
      brand: 'Sanofi',
      name: 'Эссенциале форте Н №30 капс.',
      period: 'с 5–25 мая',
      restrictions: 'Новый бренд в каталоге',
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFFBE3E3), Color(0xFFC5292B)],
        ),
        label: 'ЭССЕНЦИАЛЕ',
        accent: Color(0xFFFFFFFF),
      ),
      isNew: true,
    ),
    Product(
      id: 8,
      brand: 'AIGP',
      name: 'AIGP Лифта 20 мг №4 табл.',
      period: 'с 1–31 мая',
      bought: 0,
      tiers: [
        Tier(qty: 1, label: 'от 1 шт.', price: '700₸'),
        Tier(qty: 10, label: 'от 10 шт.', price: '900₸'),
        Tier(qty: 20, label: 'от 20 шт.', price: '1100₸'),
      ],
      bonuses: ['Доп. бонус +500 ₸ за 3 порог'],
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1A2C5C), Color(0xFF2A6FDB)],
        ),
        label: 'ЛИФТА 20 мг',
        sub: 'Тадалафил / Tadalafil',
        maker: 'ABDI IBRAHIM',
      ),
      contest: true,
    ),
    Product(
      id: 9,
      brand: 'Kimberly Clark',
      name: 'Huggies Elite Soft № 4',
      period: 'с 1–31 мая',
      bought: 0,
      tiers: [
        Tier(qty: 1, label: 'от 1 уп.', price: '1500₸'),
        Tier(qty: 5, label: 'от 5 уп.', price: '1800₸'),
      ],
      bonuses: ['Конкурс «Семейный кэшбэк» — приз 50 000 ₸'],
      pkg: ProductPackage(
        background: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFF2E2BA), Color(0xFFE2A14C)],
        ),
        label: 'HUGGIES',
        accent: Color(0xFF7E480A),
      ),
      contest: true,
    ),
  ];

  static const _brands = <String>[
    'Polpharma Santo', 'AIGP', 'Alpen Pharma', 'Alvogen', 'Asfarma',
    'Danhson', 'Danielli', 'Dincom', 'Glenmark', 'Haleon',
    'Health Rising', 'Kazbiotech', 'Kimberly Clark', 'Micfarm',
    'Novartis', 'Pfizer', 'Sanofi',
  ];

  static const _sortOptions = <String>[
    'Сначала новые акции',
    'По названию (А–Я)',
    'По типу акции (по возрастанию)',
    'По типу акции (по убыванию)',
  ];
}
