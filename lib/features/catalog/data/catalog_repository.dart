import 'catalog_models.dart';

/// Источник реального каталога. Реализации:
///  - [MockCatalogRepository] — демо-список (офлайн-разработка);
///  - [ApiCatalogRepository] — бэкенд `/api/mobile/catalog/*` (прокси Medusa).
abstract interface class CatalogRepository {
  Future<CatalogPage> search({String? q, int limit, int offset});
  Future<CatalogProductDetail> detail(String id);

  /// Все категории каталога с иерархией (parentId) — для дерева фильтра.
  Future<List<MobileCategory>> categories();

  /// Рекомендации к товару (ДОП.3b): «Альтернативы» (замены) + «Дополнения» (кросс-селл).
  Future<CatalogRecommendations> recommendations(String id);

  /// Глобальные пулы для пилюль ленты: «Альтернативы» (замены) + «Дополнения» (кросс-селл).
  Future<CatalogRecommendationPools> recommendationPools();
}
