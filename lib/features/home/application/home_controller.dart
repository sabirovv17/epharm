import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/home_repository.dart';

final homeRepositoryProvider = Provider<HomeRepository>((ref) => HomeRepository());

final promoListProvider = FutureProvider<List<Promo>>(
  (ref) => ref.read(homeRepositoryProvider).loadPromos(),
);

final productListProvider = FutureProvider<List<Product>>(
  (ref) => ref.read(homeRepositoryProvider).loadProducts(),
);

/// Активный chip-фильтр в строке фильтров.
enum HomeChip { all, isNew, contest }

class HomeChipNotifier extends Notifier<HomeChip> {
  @override
  HomeChip build() => HomeChip.all;
  void set(HomeChip c) => state = c;
}

final homeChipProvider = NotifierProvider<HomeChipNotifier, HomeChip>(
  HomeChipNotifier.new,
);

// ─── Сортировка ────────────────────────────────────────────────────────────

/// Опции сортировки в Sort sheet. Дефолтное значение — `newestFirst`
/// (товары с `isNew == true` поднимаются наверх).
enum SortOption {
  /// Сначала новые акции (`Product.isNew == true`).
  newestFirst,

  /// По названию (алфавитно А-Я по `Product.name`).
  alphabetical,

  /// По типу акции по возрастанию минимальной цены tier.
  promoAsc,

  /// По типу акции по убыванию минимальной цены tier.
  promoDesc,
}

extension SortOptionLabel on SortOption {
  String get label => switch (this) {
        SortOption.newestFirst => 'Сначала новые акции',
        SortOption.alphabetical => 'По названию (А-Я)',
        SortOption.promoAsc => 'По типу акции (по возрастанию)',
        SortOption.promoDesc => 'По типу акции (по убыванию)',
      };
}

class SortNotifier extends Notifier<SortOption> {
  @override
  SortOption build() => SortOption.newestFirst;
  void set(SortOption v) => state = v;
}

final homeSortProvider = NotifierProvider<SortNotifier, SortOption>(
  SortNotifier.new,
);

// ─── Бренды (множественный выбор) ──────────────────────────────────────────

/// Курированный список популярных pharma-брендов в Казахстане.
///
/// Используется в Brand bottom-sheet. Не все из них присутствуют в mock-данных
/// `Product.brand` — это ОК, пользователь видит реалистичный каталог и может
/// «применить фильтр», что вернёт ноль товаров (для UI-проверки empty-state).
const kKzPharmaBrands = <String>[
  'AIGP',
  'Sopharma',
  'Sandoz',
  'Sanofi',
  'Bayer',
  'Berlin-Chemie',
  'KRKA',
  'Gedeon Richter',
  'Nobel Pharma',
  'Polpharma Santo',
  'Santo',
  'Egis',
  'Sun Pharma',
  'Teva',
  'Zentiva',
  'Glenmark',
  'Natrol',
  'Haleon',
  'Alpen Pharma',
  'Kimberly Clark',
  'Adipharm',
  'Romat',
];

class SelectedBrandsNotifier extends Notifier<Set<String>> {
  @override
  Set<String> build() => <String>{};

  void toggle(String brand) {
    final next = {...state};
    if (next.contains(brand)) {
      next.remove(brand);
    } else {
      next.add(brand);
    }
    state = next;
  }

  void clear() => state = <String>{};

  void replace(Set<String> brands) => state = {...brands};
}

final selectedBrandsProvider =
    NotifierProvider<SelectedBrandsNotifier, Set<String>>(
  SelectedBrandsNotifier.new,
);

// ─── Поиск ─────────────────────────────────────────────────────────────────

class SearchQueryNotifier extends Notifier<String> {
  @override
  String build() => '';
  void set(String v) => state = v;
}

final searchQueryProvider = NotifierProvider<SearchQueryNotifier, String>(
  SearchQueryNotifier.new,
);

// ─── Итоговый фильтр ───────────────────────────────────────────────────────

/// Применяет к списку продуктов все активные фильтры:
///   • chip (all / new / contest)
///   • selectedBrands (если непусто — оставляем только их)
///   • searchQuery (case-insensitive по name + brand)
///   • sort (порядок результата)
List<Product> applyHomeFilters({
  required List<Product> products,
  required HomeChip chip,
  required Set<String> brands,
  required String query,
  required SortOption sort,
}) {
  Iterable<Product> result = products;

  // chip
  result = switch (chip) {
    HomeChip.all => result,
    HomeChip.isNew => result.where((p) => p.isNew),
    HomeChip.contest => result.where((p) => p.contest),
  };

  // brand filter
  if (brands.isNotEmpty) {
    result = result.where((p) => brands.contains(p.brand));
  }

  // search query
  final q = query.trim().toLowerCase();
  if (q.isNotEmpty) {
    result = result.where(
      (p) => p.name.toLowerCase().contains(q) || p.brand.toLowerCase().contains(q),
    );
  }

  final list = result.toList();

  int minTierPrice(Product p) {
    if (p.tiers.isEmpty) return 0;
    final prices = p.tiers
        .map((t) => int.tryParse(t.price.replaceAll(RegExp(r'[^0-9]'), '')) ?? 0)
        .toList();
    return prices.reduce((a, b) => a < b ? a : b);
  }

  switch (sort) {
    case SortOption.newestFirst:
      list.sort((a, b) => (b.isNew ? 1 : 0).compareTo(a.isNew ? 1 : 0));
    case SortOption.alphabetical:
      list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    case SortOption.promoAsc:
      list.sort((a, b) => minTierPrice(a).compareTo(minTierPrice(b)));
    case SortOption.promoDesc:
      list.sort((a, b) => minTierPrice(b).compareTo(minTierPrice(a)));
  }
  return list;
}
