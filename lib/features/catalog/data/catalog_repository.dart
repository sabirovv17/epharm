import 'catalog_models.dart';

/// Источник реального каталога. Реализации:
///  - [MockCatalogRepository] — демо-список (офлайн-разработка);
///  - [ApiCatalogRepository] — бэкенд `/api/mobile/catalog/*` (прокси Medusa).
abstract interface class CatalogRepository {
  Future<CatalogPage> search({String? q, int limit, int offset});
  Future<CatalogProductDetail> detail(String id);
}
