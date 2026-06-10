import 'nearby_pharmacies.dart';

/// Источник аптек для AddressSheet. Реализации:
///  - [MockPharmacyRepository] — статический список (офлайн-демо);
///  - [ApiPharmacyRepository] — реестр бэкенда (`GET /api/mobile/pharmacies`).
abstract interface class PharmacyRepository {
  Future<List<NearbyPharmacy>> list();
}
