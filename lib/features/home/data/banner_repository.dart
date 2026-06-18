import '../../../core/network/api_client.dart';
import 'banner_model.dart';

/// Источник баннеров главной. Реализации: реальный API и офлайн-mock.
abstract class BannerRepository {
  Future<List<BannerModel>> list();
}

/// Баннеры из бэкенда (`GET /api/mobile/banners`, публичный — auth не нужен).
/// Калька с PromotionsRepository: getJsonList → `List<BannerModel>.fromJson`.
class ApiBannerRepository implements BannerRepository {
  ApiBannerRepository(this._client);

  final ApiClient _client;

  @override
  Future<List<BannerModel>> list() async {
    final arr = await _client.getJsonList('/api/mobile/banners', auth: false);
    return arr
        .whereType<Map>()
        .map((m) => BannerModel.fromJson(m.cast<String, dynamic>()))
        .toList();
  }
}

/// Офлайн-mock: пустой список (карусель скрывается при пустоте). Можно добавить
/// 1-2 демо-баннера для офлайн-показа — пока отдаём пусто, чтобы шапка вставала
/// сразу под поиск без пустой щели.
class MockBannerRepository implements BannerRepository {
  @override
  Future<List<BannerModel>> list() async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    return const <BannerModel>[];
  }
}
