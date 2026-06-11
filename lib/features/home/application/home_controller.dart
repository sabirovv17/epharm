import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../catalog/application/catalog_controller.dart';
import '../../catalog/data/catalog_models.dart';
import '../data/home_repository.dart';

final homeRepositoryProvider = Provider<HomeRepository>((ref) => HomeRepository());

/// Промо-карусель — НАШИ бонус-акции (отдельная сущность от реального каталога).
final promoListProvider = FutureProvider<List<Promo>>(
  (ref) => ref.read(homeRepositoryProvider).loadPromos(),
);

/// Мок-промо-товары (бонус-акции) — используются ТОЛЬКО при выборе акций для
/// чека ([promo_picker_screen]). В ленте Home их заменил реальный каталог.
final productListProvider = FutureProvider<List<Product>>(
  (ref) => ref.read(homeRepositoryProvider).loadProducts(),
);

// ─── Лента-каталог ───────────────────────────────────────────────────────────
// Канал «Сайт» сейчас ~77 товаров → грузим ВЕСЬ каталог один раз и фильтруем на
// клиенте (бренд / категория / поиск / сортировка) — мгновенно, без round-trip на
// каждый фильтр. Тот же паттерн, что был с mock-продуктами (applyHomeFilters).
// Если каталог вырастет в тысячи — фильтрацию переносим на сервер.

/// Все товары каталога (постранично, до safety-cap). Источник ленты Home.
final homeCatalogProvider = FutureProvider<List<CatalogProduct>>((ref) async {
  final repo = ref.read(catalogRepositoryProvider);
  const pageSize = 100;
  const safetyCap = 1000; // защита, если канал внезапно разрастётся
  final all = <CatalogProduct>[];
  var offset = 0;
  while (true) {
    final page = await repo.search(q: null, limit: pageSize, offset: offset);
    all.addAll(page.items);
    offset += page.items.length;
    final done = page.items.isEmpty ||
        all.length >= page.total ||
        all.length >= safetyCap;
    if (done) break;
  }
  return all;
});

/// Структурные псевдо-категории Medusa, которые НЕ показываем в фильтре.
const _hiddenCategories = {'сайт'};

/// Значимое значение бренда/категории: пустые и плейсхолдеры 1С отбрасываем.
bool _meaningful(String s) {
  final t = s.trim();
  return t.isNotEmpty &&
      t != '-' &&
      t != '—' &&
      t != '_' &&
      t.toLowerCase() != 'none';
}

/// Различные бренды из загруженного каталога (отсортированы, без мусора).
final homeBrandsProvider = Provider<List<String>>((ref) {
  final products = ref.watch(homeCatalogProvider).valueOrNull ?? const [];
  final set = <String>{};
  for (final p in products) {
    final b = p.brand;
    if (b != null && _meaningful(b)) set.add(b.trim());
  }
  return set.toList()
    ..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
});

/// Различные категории из загруженного каталога (без структурной «Сайт»).
final homeCategoriesProvider = Provider<List<String>>((ref) {
  final products = ref.watch(homeCatalogProvider).valueOrNull ?? const [];
  final set = <String>{};
  for (final p in products) {
    for (final c in p.categories) {
      if (_meaningful(c) &&
          !_hiddenCategories.contains(c.trim().toLowerCase())) {
        set.add(c.trim());
      }
    }
  }
  return set.toList()
    ..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
});

// ─── Сортировка ──────────────────────────────────────────────────────────────
// Только по названию: цены и дат новизны в реальном каталоге пока нет
// (calculated_price=null у всех, created_at одинаковый) — сортировать не по чему.

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

// ─── Поиск ───────────────────────────────────────────────────────────────────

class SearchQueryNotifier extends Notifier<String> {
  @override
  String build() => '';
  void set(String v) => state = v;
}

final searchQueryProvider = NotifierProvider<SearchQueryNotifier, String>(
  SearchQueryNotifier.new,
);

// ─── Итоговый клиентский фильтр ───────────────────────────────────────────────

/// Применяет к каталогу все активные фильтры:
///   • brands     — если непусто, оставляем только выбранные бренды
///   • categories — если непусто, товар проходит если хотя бы одна его категория выбрана
///   • query      — поиск по названию + бренду + МНН (case-insensitive)
///   • sort       — по названию А-Я / Я-А
List<CatalogProduct> applyCatalogFilters({
  required List<CatalogProduct> products,
  required Set<String> brands,
  required Set<String> categories,
  required String query,
  required CatalogSort sort,
}) {
  Iterable<CatalogProduct> result = products;

  if (brands.isNotEmpty) {
    result = result.where(
      (p) => p.brand != null && brands.contains(p.brand!.trim()),
    );
  }
  if (categories.isNotEmpty) {
    result = result.where(
      (p) => p.categories.any((c) => categories.contains(c.trim())),
    );
  }

  final q = query.trim().toLowerCase();
  if (q.isNotEmpty) {
    result = result.where(
      (p) =>
          p.name.toLowerCase().contains(q) ||
          (p.brand ?? '').toLowerCase().contains(q) ||
          (p.mnn ?? '').toLowerCase().contains(q),
    );
  }

  final list = result.toList();
  switch (sort) {
    case CatalogSort.nameAsc:
      list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    case CatalogSort.nameDesc:
      list.sort((a, b) => b.name.toLowerCase().compareTo(a.name.toLowerCase()));
  }
  return list;
}
