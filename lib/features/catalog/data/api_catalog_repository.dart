import '../../../core/network/api_client.dart';
import 'catalog_models.dart';
import 'catalog_repository.dart';

/// Реальный каталог из бэкенда (`/api/mobile/catalog/*`, под JWT). Бэкенд проксирует
/// Medusa-витрину — телефон не знает про внешний сервис и не хранит его ключ.
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
}
