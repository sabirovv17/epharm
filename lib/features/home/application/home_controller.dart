import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/home_repository.dart';

final homeRepositoryProvider = Provider<HomeRepository>((ref) => HomeRepository());

/// Промо-карусель (баннеры) — отдельная сущность от ленты акций.
final promoListProvider = FutureProvider<List<Promo>>(
  (ref) => ref.read(homeRepositoryProvider).loadPromos(),
);

// NB: productListProvider (мок-каталог) удалён — и лента Home, и пикер чека
// теперь берут реальный пул акций из promotionsProvider.

// ─── Сортировка ленты ────────────────────────────────────────────────────────
// По названию (А-Я / Я-А). Применяется к пулу промо-акций (applyPromotionFilters).

enum CatalogSort { nameAsc, nameDesc }

extension CatalogSortLabel on CatalogSort {
  String get label => switch (this) {
        CatalogSort.nameAsc => 'По названию (А-Я)',
        CatalogSort.nameDesc => 'По названию (Я-А)',
      };
}

class CatalogSortNotifier extends Notifier<CatalogSort> {
  @override
  CatalogSort build() => CatalogSort.nameAsc;
  void set(CatalogSort v) => state = v;
}

final homeSortProvider = NotifierProvider<CatalogSortNotifier, CatalogSort>(
  CatalogSortNotifier.new,
);

// ─── Множественный выбор (бренды + категории) ────────────────────────────────

class StringSetNotifier extends Notifier<Set<String>> {
  @override
  Set<String> build() => <String>{};
  void clear() => state = <String>{};
  void replace(Set<String> v) => state = {...v};
}

final selectedBrandsProvider =
    NotifierProvider<StringSetNotifier, Set<String>>(StringSetNotifier.new);

final selectedCategoriesProvider =
    NotifierProvider<StringSetNotifier, Set<String>>(StringSetNotifier.new);

// ─── Пилюли «Альтернативы»/«Дополнения» (пулы рекомендаций) ──────────────────
// Режим ленты: обычные акции (none) ИЛИ пул замен (alternatives) ИЛИ пул допов
// (crosssells). Пилюли — взаимоисключающие: активна максимум одна.

enum RecoPool { none, alternatives, crosssells }

class RecoPoolNotifier extends Notifier<RecoPool> {
  @override
  RecoPool build() => RecoPool.none;

  /// Тап по пилюле: повторный тап по активной выключает её (назад к ленте акций).
  void toggle(RecoPool p) => state = state == p ? RecoPool.none : p;
}

final homeRecoPoolProvider =
    NotifierProvider<RecoPoolNotifier, RecoPool>(RecoPoolNotifier.new);

// ─── Поиск ───────────────────────────────────────────────────────────────────

class SearchQueryNotifier extends Notifier<String> {
  @override
  String build() => '';
  void set(String v) => state = v;
}

final searchQueryProvider = NotifierProvider<SearchQueryNotifier, String>(
  SearchQueryNotifier.new,
);
