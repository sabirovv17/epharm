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

// NB: пагинированный catalogProvider/CatalogState/CatalogNotifier (для отдельного
// экрана каталога) удалён — лента Home грузит весь каталог разом через
// homeCatalogProvider и фильтрует на клиенте. Здесь остались только repository- и
// detail-провайдеры, которые переиспользуют лента и detail-sheet.

/// Детальная карточка товара по medusa-id.
final catalogDetailProvider =
    FutureProvider.autoDispose.family<CatalogProductDetail, String>(
  (ref, id) => ref.read(catalogRepositoryProvider).detail(id),
);
