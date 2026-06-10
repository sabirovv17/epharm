import '../../../core/network/api_client.dart';
import 'nearby_pharmacies.dart';
import 'pharmacy_repository.dart';

/// Реальный реестр аптек из бэкенда: `GET /api/mobile/pharmacies` (под JWT).
/// Возвращает активные аптеки с реальными адресами и цветом сети.
class ApiPharmacyRepository implements PharmacyRepository {
  ApiPharmacyRepository(this._client);

  final ApiClient _client;

  @override
  Future<List<NearbyPharmacy>> list() async {
    final raw = await _client.getJsonList('/api/mobile/pharmacies');
    return raw
        .whereType<Map<String, dynamic>>()
        .map(NearbyPharmacy.fromApi)
        .toList();
  }
}
