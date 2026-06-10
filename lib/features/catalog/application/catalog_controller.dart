import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../data/api_catalog_repository.dart';
import '../data/catalog_models.dart';
import '../data/catalog_repository.dart';
import '../data/mock_catalog_repository.dart';

/// Реальный каталог при USE_API=true, иначе demo-mock (офлайн).
final catalogRepositoryProvider = Provider<CatalogRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiCatalogRepository(ref.read(apiClientProvider));
  }
  return MockCatalogRepository();
});

/// Состояние экрана каталога: накопленные товары + признак догрузки + текущий запрос.
class CatalogState {
  const CatalogState({
    required this.items,
    required this.total,
    required this.query,
    this.loadingMore = false,
  });

  final List<CatalogProduct> items;
  final int total;
  final String query;
  final bool loadingMore;

  bool get hasMore => items.length < total;

  CatalogState copyWith({
    List<CatalogProduct>? items,
    int? total,
    String? query,
    bool? loadingMore,
  }) =>
      CatalogState(
        items: items ?? this.items,
        total: total ?? this.total,
        query: query ?? this.query,
        loadingMore: loadingMore ?? this.loadingMore,
      );
}

class CatalogNotifier extends AutoDisposeAsyncNotifier<CatalogState> {
  static const pageSize = 24;

  @override
  Future<CatalogState> build() => _fetch('');

  Future<CatalogState> _fetch(String q) async {
    final page =
        await ref.read(catalogRepositoryProvider).search(q: q, limit: pageSize, offset: 0);
    return CatalogState(items: page.items, total: page.total, query: q);
  }

  /// Новый поиск: показываем спиннер, но сохраняем прошлые товары под ним
  /// (copyWithPrevious) — список не «мигает» в пустоту между запросами.
  Future<void> search(String q) async {
    state = const AsyncLoading<CatalogState>().copyWithPrevious(state);
    state = await AsyncValue.guard(() => _fetch(q));
  }

  Future<void> refresh() => search(state.valueOrNull?.query ?? '');

  /// Догрузка следующей страницы (бесконечный скролл).
  Future<void> loadMore() async {
    final cur = state.valueOrNull;
    if (cur == null || cur.loadingMore || !cur.hasMore) return;
    state = AsyncData(cur.copyWith(loadingMore: true));
    try {
      final page = await ref
          .read(catalogRepositoryProvider)
          .search(q: cur.query, limit: pageSize, offset: cur.items.length);
      state = AsyncData(
        CatalogState(
          items: [...cur.items, ...page.items],
          total: page.total,
          query: cur.query,
        ),
      );
    } catch (_) {
      // Догрузка не критична: гасим индикатор, оставляем уже показанное.
      state = AsyncData(cur.copyWith(loadingMore: false));
    }
  }
}

final catalogProvider =
    AsyncNotifierProvider.autoDispose<CatalogNotifier, CatalogState>(
  CatalogNotifier.new,
);

/// Детальная карточка товара по medusa-id.
final catalogDetailProvider =
    FutureProvider.autoDispose.family<CatalogProductDetail, String>(
  (ref, id) => ref.read(catalogRepositoryProvider).detail(id),
);
