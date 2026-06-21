import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_client.dart';
import '../../home/application/home_controller.dart'
    show
        CatalogSort,
        debouncedSearchQueryProvider,
        homeSortProvider,
        selectedBrandsProvider,
        selectedCategoriesProvider;
import '../data/promotion_models.dart';
import '../data/promotions_repository.dart';

final promotionsRepositoryProvider = Provider<PromotionsRepository>(
  (ref) => PromotionsRepository(ref.read(apiClientProvider)),
);

/// Лента промо-акций — источник главной страницы фармацевта (и пула для чека).
final promotionsProvider = FutureProvider<List<Promotion>>(
  (ref) => ref.read(promotionsRepositoryProvider).list(),
);

bool _meaningful(String s) {
  final t = s.trim();
  return t.isNotEmpty && t != '-' && t != '—' && t != '_' && t.toLowerCase() != 'none';
}

/// Максимальный бонус промо (по всем порогам) — как считается в карточке
/// (promo_product_card.dart:305). Используется сортировкой «По сумме бонуса».
int _maxBonus(Promotion p) =>
    p.tiers.fold<int>(0, (m, t) => t.bonus > m ? t.bonus : m);

/// Бренды для фильтра — из ПУЛА промо (а не из всего каталога Medusa).
final promoBrandsProvider = Provider<List<String>>((ref) {
  final items = ref.watch(promotionsProvider).valueOrNull ?? const [];
  final set = <String>{};
  for (final p in items) {
    final b = p.brand;
    if (b != null && _meaningful(b)) set.add(b.trim());
  }
  return set.toList()..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
});

/// Категории для фильтра — из пула промо (без структурной «Сайт»).
final promoCategoriesProvider = Provider<List<String>>((ref) {
  final items = ref.watch(promotionsProvider).valueOrNull ?? const [];
  final set = <String>{};
  for (final p in items) {
    for (final c in p.categories) {
      if (_meaningful(c) && c.trim().toLowerCase() != 'сайт') set.add(c.trim());
    }
  }
  return set.toList()..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
});

/// Применяет фильтры (бренд/категория/поиск/сортировка) к пулу промо-товаров.
List<Promotion> applyPromotionFilters({
  required List<Promotion> items,
  required Set<String> brands,
  required Set<String> categories,
  required String query,
  required CatalogSort sort,
}) {
  Iterable<Promotion> r = items;
  if (brands.isNotEmpty) {
    r = r.where((p) => p.brand != null && brands.contains(p.brand!.trim()));
  }
  if (categories.isNotEmpty) {
    r = r.where((p) => p.categories.any((c) => categories.contains(c.trim())));
  }
  final q = query.trim().toLowerCase();
  if (q.isNotEmpty) {
    r = r.where((p) =>
        p.name.toLowerCase().contains(q) ||
        (p.brand ?? '').toLowerCase().contains(q) ||
        (p.mnn ?? '').toLowerCase().contains(q));
  }
  final list = r.toList();
  switch (sort) {
    case CatalogSort.nameAsc:
      list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    case CatalogSort.nameDesc:
      list.sort((a, b) => b.name.toLowerCase().compareTo(a.name.toLowerCase()));
    case CatalogSort.bonusDesc:
      // По убыванию МАКСИМАЛЬНОГО бонуса промо (как считается в карточке —
      // promo_product_card.dart:305). Промо без порогов → бонус 0.
      list.sort((a, b) => _maxBonus(b).compareTo(_maxBonus(a)));
  }
  return list;
}

/// Уже отфильтрованная/отсортированная лента акций. Вынесено в отдельный
/// провайдер, чтобы:
///  - пересчёт фильтра+сортировки шёл ТОЛЬКО при смене входов (а не на каждый
///    ребилд экрана/кадр ввода);
///  - ввод поиска (через [debouncedSearchQueryProvider]) перестраивал ТОЛЬКО
///    грид ленты, а не шапку/карусель/поиск/чипы Home.
/// `whenData` сохраняет loading/error промо — экран сам покажет спиннер/ретрай.
final filteredPromotionsProvider = Provider<AsyncValue<List<Promotion>>>((ref) {
  final promos = ref.watch(promotionsProvider);
  final brands = ref.watch(selectedBrandsProvider);
  final categories = ref.watch(selectedCategoriesProvider);
  final query = ref.watch(debouncedSearchQueryProvider);
  final sort = ref.watch(homeSortProvider);
  return promos.whenData(
    (items) => applyPromotionFilters(
      items: items,
      brands: brands,
      categories: categories,
      query: query,
      sort: sort,
    ),
  );
});
