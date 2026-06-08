import '../../../core/network/api_client.dart';
import '../../auth/domain/user.dart';

/// Источник профиля фармацевта (баланс/тир/статус) с backend `/api/mobile/me`.
abstract interface class MeRepository {
  Future<User> fetchMe();
}

class ApiMeRepository implements MeRepository {
  ApiMeRepository(this._client);

  final ApiClient _client;

  @override
  Future<User> fetchMe() async {
    final json = await _client.getJson('/api/mobile/me');
    return User.fromMeJson(json);
  }
}
