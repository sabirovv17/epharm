import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../data/banner_model.dart';
import '../data/banner_repository.dart';

/// Репозиторий баннеров: реальный API при USE_API=true, иначе офлайн-mock.
final bannerRepositoryProvider = Provider<BannerRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiBannerRepository(ref.read(apiClientProvider));
  }
  return MockBannerRepository();
});

/// Промо-карусель главной = баннеры из админки (`GET /api/mobile/banners`).
/// Пустой список → карусель скрывается (см. home_screen.dart).
final bannerListProvider = FutureProvider<List<BannerModel>>(
  (ref) => ref.read(bannerRepositoryProvider).list(),
);

// NB: productListProvider (мок-каталог) удалён — и лента Home, и пикер чека
// теперь берут реальный пул акций из promotionsProvider.

// ─── Сортировка ленты ────────────────────────────────────────────────────────
// По названию (А-Я / Я-А) или по убыванию бонуса. Применяется к пулу промо-акций
// (applyPromotionFilters).

enum CatalogSort { nameAsc, nameDesc, bonusDesc }

extension CatalogSortLabel on CatalogSort {
  String get label => switch (this) {
        CatalogSort.nameAsc => 'По названию (А-Я)',
        CatalogSort.nameDesc => 'По названию (Я-А)',
        CatalogSort.bonusDesc => 'По сумме бонуса',
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

/// Дебаунс поискового запроса: меняется не на каждый символ, а через паузу
/// ~280мс. Так фильтрация ленты и ребилд грид-карточек не дёргаются на каждый
/// keystroke — ввод остаётся плавным (саму строку TextField рисует мгновенно).
/// Очистку (пустую строку) применяем сразу — отклик на сброс важнее.
class DebouncedSearchNotifier extends Notifier<String> {
  Timer? _timer;

  @override
  String build() {
    ref.listen(searchQueryProvider, (_, next) {
      _timer?.cancel();
      if (next.isEmpty) {
        state = '';
        return;
      }
      _timer = Timer(const Duration(milliseconds: 280), () => state = next);
    });
    ref.onDispose(() => _timer?.cancel());
    return '';
  }
}

final debouncedSearchQueryProvider =
    NotifierProvider<DebouncedSearchNotifier, String>(
  DebouncedSearchNotifier.new,
);
