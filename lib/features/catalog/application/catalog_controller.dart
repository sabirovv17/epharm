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

// NB: пагинированный catalogProvider/CatalogState/CatalogNotifier удалён. Лента Home
// теперь = пул промо-акций (promotionsProvider), а не весь каталог. Здесь остались
// только repository- и detail-провайдеры, которые переиспользует detail-sheet товара
// (его открывают карточки промо по medusa product id).

/// Детальная карточка товара по medusa-id.
final catalogDetailProvider =
    FutureProvider.autoDispose.family<CatalogProductDetail, String>(
  (ref, id) => ref.read(catalogRepositoryProvider).detail(id),
);

/// Категории каталога с иерархией (parentId) — для дерева в фильтре категорий.
/// Кешируется (не autoDispose) — каталог категорий меняется редко.
final catalogCategoriesProvider = FutureProvider<List<MobileCategory>>(
  (ref) => ref.read(catalogRepositoryProvider).categories(),
);
