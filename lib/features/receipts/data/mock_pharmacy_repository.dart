import 'nearby_pharmacies.dart';
import 'pharmacy_repository.dart';

/// Офлайн-реализация: фиксированный список аптек Алматы (см. [nearbyPharmacies]).
class MockPharmacyRepository implements PharmacyRepository {
  @override
  Future<List<NearbyPharmacy>> list() async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    return nearbyPharmacies;
  }
}
