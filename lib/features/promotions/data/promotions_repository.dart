import '../../../core/network/api_client.dart';
import 'promotion_models.dart';

/// Лента промо-акций из бэкенда (`GET /api/mobile/promotions`, публичный).
/// Активные промо-кампании из админки, смерженные с живыми данными витрины Medusa.
class PromotionsRepository {
  PromotionsRepository(this._client);

  final ApiClient _client;

  Future<List<Promotion>> list() async {
    final arr = await _client.getJsonList('/api/mobile/promotions');
    return arr
        .whereType<Map>()
        .map((m) => Promotion.fromJson(m.cast<String, dynamic>()))
        .toList();
  }
}
