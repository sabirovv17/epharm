import '../../../core/network/api_client.dart';
import 'catalog_models.dart';
import 'catalog_repository.dart';

/// Каталог акционных товаров из бэкенда (`/api/mobile/catalog/*`). Backend
/// выбирает опубликованные на сайте товары с действующей акцией; телефон не
/// соединяется с внешним источником каталога и не хранит его ключ.
class ApiCatalogRepository implements CatalogRepository {
  ApiCatalogRepository(this._client);

  final ApiClient _client;

  @override
  Future<CatalogPage> search({String? q, int limit = 24, int offset = 0}) async {
    final params = <String>['limit=$limit', 'offset=$offset'];
    final needle = q?.trim() ?? '';
    if (needle.isNotEmpty) {
      params.add('q=${Uri.encodeQueryComponent(needle)}');
    }
    final json = await _client.getJson('/api/mobile/catalog/products?${params.join('&')}');
    return CatalogPage.fromJson(json);
  }

  @override
  Future<CatalogProductDetail> detail(String id) async {
    final json = await _client.getJson(
      '/api/mobile/catalog/products/${Uri.encodeComponent(id)}',
    );
    return CatalogProductDetail.fromJson(json);
  }

  @override
  Future<List<MobileCategory>> categories() async {
    final raw = await _client.getJsonList('/api/mobile/catalog/categories');
    return raw
        .whereType<Map<String, dynamic>>()
        .map(MobileCategory.fromJson)
        .toList();
  }

  @override
  Future<CatalogRecommendations> recommendations(String id) async {
    final json = await _client.getJson(
      '/api/mobile/catalog/products/${Uri.encodeComponent(id)}/recommendations',
    );
    return CatalogRecommendations.fromJson(json);
  }

  @override
  Future<CatalogRecommendationPools> recommendationPools() async {
    final json =
        await _client.getJson('/api/mobile/catalog/recommendation-pools');
    return CatalogRecommendationPools.fromJson(json);
  }
}
